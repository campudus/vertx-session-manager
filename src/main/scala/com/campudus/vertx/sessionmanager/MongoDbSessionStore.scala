package com.campudus.vertx.sessionmanager

import java.util.UUID

import scala.collection.JavaConversions.asScalaSet
import scala.collection.JavaConversions.iterableAsScalaIterable

import org.vertx.java.core.AsyncResult
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.Handler
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.json.JsonElement
import org.vertx.java.core.json.JsonObject

class MongoDbSessionStore(sm: SessionManager, address: String, collection: String) extends SessionManagerSessionStore with VertxScalaHelpers {

  val vertx = sm.getVertx()
  val logger = sm.getContainer().getLogger()

  // TODO use findAndModify as soon as available
  def clearAllSessions(resultHandler: AsyncResultHandler[Boolean]) {
    import scala.collection.JavaConversions._
    sendToPersistor(mongoAction("find").putObject("matcher", json),
      withoutMongoErrorDo(resultHandler) {
        reply: JsonObject =>
          val results = reply.getArray("results")
          for (result <- results) {
            val res = result.asInstanceOf[JsonObject]
            sm.clearSession(res.getString("sessionId"), res.getNumber("sessionTimer").longValue(), res.getObject("data"))
          }
          sendToPersistor(mongoAction("delete").putObject("matcher", json), withoutMongoErrorDo(resultHandler) {
            reply: JsonObject =>
              resultHandler.handle(true)
          })
      })
  }

  def getMatches(data: JsonObject, resultHandler: AsyncResultHandler[JsonArray]) {
    sendToPersistor(mongoAction("find").putObject("matcher", toDataNotation(data)),
      withoutMongoErrorDo(resultHandler) {
        obj =>
          resultHandler.handle(obj.getArray("results"))
      })
  }

  def getOpenSessions(resultHandler: AsyncResultHandler[Long]) {
    sendToPersistor(mongoAction("count"),
      withoutMongoErrorDo(resultHandler) {
        obj =>
          resultHandler.handle(obj.getNumber("count").longValue())
      })
  }

  def getSessionData(sessionId: String, fields: JsonArray, resultHandler: AsyncResultHandler[JsonObject]) {
    val searchFor = json.putString("sessionId", sessionId)
    val action = mongoAction("findone").putObject("matcher", searchFor).
      putObject("keys", jsonArrayToFieldSelection(fields))
    sendToPersistor(action,
      withoutMongoErrorDo(resultHandler) {
        result =>
          result.getObject("result") match {
            case null =>
              resultHandler.handle(new AsyncResult(SessionException.gone()))
            case foundSession =>
              resultHandler.handle(foundSession)
          }
      })
  }

  // TODO find and update with findAndModify when available
  def putSession(sessionId: String, data: JsonObject, resultHandler: AsyncResultHandler[Boolean]) {
    val searchFor = json.putString("sessionId", sessionId)
    sendToPersistor(mongoAction("findone").putObject("matcher", searchFor), withoutMongoErrorDo(resultHandler) {
      result =>
        result.getObject("result") match {
          case obj: JsonObject =>
            sendToPersistor(mongoAction("update").putObject("criteria", searchFor).
              putObject("objNew", json.putObject("$set",
                toDataNotation(data))),
              withoutMongoErrorDo(resultHandler) {
                updated =>
                  resultHandler.handle(true)
              })
          case _ =>
            resultHandler.handle(new AsyncResult(SessionException.gone()))
        }
    })
  }

  def removeSession(sessionId: String, timerId: Option[Long], resultHandler: AsyncResultHandler[JsonObject]) {
    val searchFor = json.putString("sessionId", sessionId)
    sendToPersistor(mongoAction("findone").
      putObject("matcher", searchFor).
      putObject("keys", json.putBoolean("data", true).putBoolean("sessionTimer", true)),
      withoutMongoErrorDo(resultHandler) {
        findResult =>
          findResult.getObject("result") match {
            case null =>
              resultHandler.handle(new AsyncResult(SessionException.gone()))
            case obj =>
              if (!timerId.isDefined || timerId.get == obj.getNumber("sessionTimer").longValue()) {
                sendToPersistor(mongoAction("delete").putObject("matcher", searchFor),
                  withoutMongoErrorDo(resultHandler) {
                    deleteResult =>
                      resultHandler.handle(
                        json.putNumber("sessionTimer", obj.getNumber("sessionTimer")).
                          putObject("session", obj.getObject("data")))
                  })
              }
          }
      })
  }

  // TODO is find needed here?
  def resetTimer(sessionId: String, newTimerId: Long, resultHandler: AsyncResultHandler[Long]) {
    val searchFor = json.putString("sessionId", sessionId)
    sendToPersistor(mongoAction("findone").
      putObject("matcher", searchFor).
      putObject("keys", json.putBoolean("sessionTimer", true)),
      withoutMongoErrorDo(resultHandler) {
        result =>
          result.getObject("result") match {
            case null =>
              resultHandler.handle(new AsyncResult(new SessionException("UNKNOWN_SESSIONID", "Could not find session with id '" + sessionId + "'.")))
            case foundSession =>
              val oldTimerId = foundSession.getNumber("sessionTimer").longValue
              sendToPersistor(mongoAction("update").
                putObject("criteria", searchFor.
                  putNumber("sessionTimer", oldTimerId)).
                putObject("objNew", json.putObject("$set",
                  json.putNumber("sessionTimer", newTimerId))),
                withoutMongoErrorDo(resultHandler) {
                  updated =>
                    resultHandler.handle(oldTimerId)
                })
          }
      })
  }

  // TODO use findAndModify to create a new session, if none available with this sessionId
  def startSession(resultHandler: AsyncResultHandler[String]) {
    val sessionId = UUID.randomUUID.toString
    val timerId = sm.createTimer(sessionId)
    sendToPersistor(mongoAction("save").putObject("document",
      json.putString("sessionId", sessionId).putNumber("sessionTimer", timerId)),
      withoutMongoErrorDo({
        errorResult: AsyncResult[String] =>
          sm.cancelTimer(timerId)
          resultHandler.handle(errorResult)
      }) {
        obj =>
          resultHandler.handle(sessionId)
      })
  }

  private def sendToPersistor(obj: JsonObject, handler: Handler[Message[JsonObject]]) =
    vertx.eventBus.send(address, obj, handler)

  private def withoutMongoErrorDo[T](resultHandlerForErrors: AsyncResultHandler[T])(fn: JsonObject => Unit): Handler[Message[JsonObject]] = {
    msg: Message[JsonObject] =>
      msg.body.getString("status") match {
        case "ok" =>
          fn(msg.body)
        case "error" =>
          resultHandlerForErrors.handle(new AsyncResult(new SessionException("MONGODB_ERROR", msg.body.getString("message"))))
      }
  }

  private def mongoAction(action: String) =
    json.putString("action", action).putString("collection", collection)

  private def toDataNotation(data: JsonObject) = {
    import scala.collection.JavaConversions._
    val newData = json
    for (fieldName <- data.getFieldNames) {
      val dataField = "data." + fieldName
      data.getField(fieldName) match {
        case x: Array[Byte] => newData.putBinary(dataField, x)
        case x: java.lang.Boolean => newData.putBoolean(dataField, x)
        case x: JsonElement => newData.putElement(dataField, x)
        case x: Number => newData.putNumber(dataField, x)
        case x: String => newData.putString(dataField, x)
      }
    }
    newData
  }

  private def jsonArrayToFieldSelection(fields: JsonArray) = {
    import scala.collection.JavaConversions._
    val json = new JsonObject
    for (field <- fields) {
      field match {
        case str: String =>
          json.putBoolean("data." + str, true)
        case _ => // ignore non-string fields
      }
    }
    json
  }

  /*
   * FIXME 'command' does not work as action in mongo persistor.
  private def mongoCommand(json: JsonObject): JsonObject =
    new JsonObject().putString("action", "command").putString("command", json.encode())
   */

  //
  //
  //  def clearAllSessions(resultHandler: AsyncResultHandler[Boolean]) {
  //    import scala.collection.JavaConversions._
  //    vertx.eventBus().send(address, mongoAction("find"), withoutMongoErrorDo(resultHandler) {
  //      obj =>
  //        val results = obj.getArray("results")
  //        for (result <- results) {
  //          result match {
  //            case res: JsonObject =>
  //              sm.clearSession(res.getString("sessionId"), res.getObject("data"))
  //            case _ =>
  //              logger.error("MongoDB saved a non-JsonObject")
  //          }
  //        }
  //        resultHandler.handle(true)
  //    })
  //  }
  //
  //  def getMatches(data: JsonObject, resultHandler: AsyncResultHandler[JsonArray]) {
  //    vertx.eventBus().send(address, mongoAction("find").putObject("matcher", json.putObject("data", data)), withoutMongoErrorDo(resultHandler) {
  //      obj =>
  //        resultHandler.handle(obj.getArray("results"))
  //    })
  //  }
  //
  //  def getOpenSessions(resultHandler: AsyncResultHandler[Long]) {
  //    vertx.eventBus().send(address, mongoAction("count"), withoutMongoErrorDo(resultHandler) {
  //      obj =>
  //        resultHandler.handle(obj.getNumber("count").longValue())
  //    })
  //  }
  //
  //  private def jsonArrayToFieldSelection(fields: JsonArray) = {
  //    import scala.collection.JavaConversions._
  //    val json = new JsonObject
  //    for (field <- fields) {
  //      field match {
  //        case str: String =>
  //          json.putNumber("data." + str, 1)
  //      }
  //    }
  //    json
  //  }
  //  def getSessionData(sessionId: String, fields: JsonArray, resultHandler: AsyncResultHandler[JsonObject]): Unit = {
  //    vertx.eventBus().send(address,
  //      json.putString("command",
  //        json.putString("findAndModify", collection).
  //          putObject("query", json.putString("sessionId", sessionId)).
  //          putObject("fields", jsonArrayToFieldSelection(fields)).
  //          putObject("update", json.putNumber("sessionTimeout", System.currentTimeMillis)).
  //          encode()), withoutMongoErrorDo(resultHandler) {
  //        obj =>
  //          val session = obj.getObject("result").getObject("data")
  //          resultHandler.handle(session)
  //      })
  //  }
  //  sharedSessions.get(sessionId) match {
  //    case null =>
  //      resultHandler.handle(new AsyncResult(new SessionException("SESSION_GONE", "Cannot get data from session '" + sessionId + "'. Session is gone.")))
  //    case sessionString =>
  //      // session is still in use, change timeout
  //      resetTimer(sessionId, { implicit res: AsyncResult[Boolean] =>
  //        if (res.succeeded() && res.result) {
  //          val session = new JsonObject(sessionString)
  //          val result = new JsonObject
  //          for (key <- fields.toArray) {
  //            session.getField(key.toString) match {
  //              case null => // Unknown field: Do not put into result
  //              case elem: JsonArray => result.putArray(key.toString, elem)
  //              case elem: Array[Byte] => result.putBinary(key.toString, elem)
  //              case elem: java.lang.Boolean => result.putBoolean(key.toString, elem)
  //              case elem: Number => result.putNumber(key.toString, elem)
  //              case elem: JsonObject => result.putObject(key.toString, elem)
  //              case elem: String => result.putString(key.toString, elem)
  //              case unknownType => // Unknown type: Do not put into result
  //            }
  //          }
  //          resultHandler.handle(new JsonObject().putObject("data", result))
  //        } else {
  //          resultHandler.handle(new AsyncResult(new Exception("Could not reset timer for session with id '" + sessionId + "'.")))
  //        }
  //      })
  //  }
  //
  // putSession:
  //    sharedSessions.get(sessionId) match {
  //      case null =>
  //        resultHandler.handle(new AsyncResult(new SessionException("SESSION_GONE", "Could not find session with id '" + sessionId + "'.")))
  //      case sessionString =>
  //        val session = new JsonObject(sessionString)
  //        session.mergeIn(data)
  //        sharedSessions.put(sessionId, session.encode)
  //        resultHandler.handle(true)
  //    }
  //  }
  //
  // removeSession:
  //    vertx.cancelTimer(sharedSessionTimeouts.remove(sessionId).toLong)
  //    resultHandler.handle(new JsonObject(sharedSessions.remove(sessionId)))
  //  }
  //
  // resetTimer:
  //  sharedSessionTimeouts.get(sessionId) match {
  //    case null =>
  //      resultHandler.handle(new AsyncResult(new SessionException("UNKNOWN_SESSIONID", "Could not find session with id '" + sessionId + "'.")))
  //    case timerId =>
  //      if (sharedSessionTimeouts.replace(sessionId, timerId, sm.createTimer(sessionId).toString)) {
  //        vertx.cancelTimer(timerId.toLong)
  //        resultHandler.handle(true)
  //      } else {
  //        resultHandler.handle(new AsyncResult(new Exception("Could not reset timer for session with id '" + sessionId + "'.")))
  //      }
  //  }
  //
  // startSession:
  //    val sessionId = UUID.randomUUID.toString
  //    vertx.send(address, mongoAction("save").putObject("document", json), withoutMongoErrorDo(resultHandler) {
  //      obj =>
  //        
  //    })
  //    sharedSessions.putIfAbsent(sessionId, "{}") match {
  //      case null =>
  //        sharedSessionTimeouts.put(sessionId, sm.createTimer(sessionId).toString)
  //        // There is no session with this uuid -> return it
  //        resultHandler.handle(sessionId)
  //      case anotherSessionId =>
  //        // There was a session with this uuid -> create a new one
  //        startSession(resultHandler)
  //    }
  //  }
}