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
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Failure

class MongoDbSessionStore(sm: SessionManager, address: String, collection: String) extends SessionManagerSessionStore with VertxScalaHelpers {
  import com.campudus.vertx.DefaultVertxExecutionContext.global

  val vertx = sm.getVertx()
  val logger = sm.getContainer().logger()

  private def checkMongoErrors(mongoReply: Message[JsonObject]): JsonObject = {
    mongoReply.body.getString("status") match {
      case "ok" => mongoReply.body
      case _ =>
        val ex = new SessionException("MONGODB_ERROR", mongoReply.body().getString("message"))
        logger.warn("Session error: " + ex)
        throw ex
    }
  }

  override def clearAllSessions(): Future[Boolean] = {
    import scala.collection.JavaConversions._
    sendToPersistor(mongoAction("find").putObject("matcher", json)) map checkMongoErrors flatMap { reply =>
      val results = reply.getArray("results")
      for (result <- results) {
        val res = result.asInstanceOf[JsonObject]
        sm.clearSession(res.getString("sessionId"), res.getNumber("sessionTimer").longValue(), res.getObject("data"))
      }
      sendToPersistor(mongoAction("delete").putObject("matcher", json)) map checkMongoErrors map (_ => true)
    }
  }

  override def getMatches(data: JsonObject): Future[JsonArray] = {
    sendToPersistor(mongoAction("find").putObject("matcher", toDataNotation(data))) map checkMongoErrors map (
      obj => obj.getArray("results"))
  }

  override def getOpenSessions(): Future[Long] = {
    sendToPersistor(mongoAction("count")) map checkMongoErrors map (obj => obj.getNumber("count").longValue)
  }

  override def getSessionData(sessionId: String, fields: JsonArray): Future[JsonObject] = {
    val searchFor = json.putString("sessionId", sessionId)
    val action = mongoAction("findone").putObject("matcher", searchFor).
      putObject("keys", jsonArrayToFieldSelection(fields))
    sendToPersistor(action) map checkMongoErrors map { result =>
      Option(result.getObject("result")) match {
        case None => throw SessionException.gone()
        case Some(foundSession) => foundSession
      }
    }
  }

  override def putSession(sessionId: String, data: JsonObject): Future[Boolean] = {
    val searchFor = json.putString("sessionId", sessionId)
    sendToPersistor(findAndModify(Some(searchFor), Some(json.putObject("$set", toDataNotation(data))))) map checkMongoErrors map { reply =>
      Option(reply.getObject("result").getObject("value")) match {
        case None => throw SessionException.gone()
        case Some(obj) => true
      }
    }
  }

  override def removeSession(sessionId: String, timerId: Option[Long]): Future[JsonObject] = {
    val searchFor = json.putString("sessionId", sessionId)

    sendToPersistor(mongoAction("findone").
      putObject("matcher", searchFor).
      putObject("keys", json
        .putBoolean("data", true)
        .putBoolean("sessionTimer", true))) map checkMongoErrors flatMap { findResult =>
      Option(findResult.getObject("result")) match {
        case None =>
          Future.failed(SessionException.gone())
        case Some(obj) =>
          // timerId does not matter since it is saved together with the session
          sendToPersistor(mongoAction("delete").putObject("matcher", searchFor)) map checkMongoErrors map {
            deleteResult =>
              json
                .putNumber("sessionTimer", obj.getNumber("sessionTimer"))
                .putObject("session", obj.getObject("data"))
          }
      }
    }
  }

  private def listToJsonStringArray(l: List[String]): JsonArray = {
    val arr = new JsonArray()
    l.foreach(arr.addString(_))
    arr
  }

  private def findAndModify(
    query: Option[JsonObject] = None,
    update: Option[JsonObject] = None,
    remove: Option[Boolean] = None,
    newFlag: Option[Boolean] = None,
    fields: List[String] = Nil,
    upsert: Option[Boolean] = None) = {

    val cmd = json.putString("findAndModify", collection)
    query.foreach(cmd.putObject("query", _))
    update.foreach(cmd.putObject("update", _))
    remove.foreach(cmd.putBoolean("remove", _))
    newFlag.foreach(cmd.putBoolean("new", _))
    if (!fields.isEmpty) {
      val jsObj = json
      fields.foreach(e => jsObj.putBoolean(e, true))
      cmd.putObject("fields", jsObj)
    }
    upsert.foreach(cmd.putBoolean("upsert", _))

    json.putString("action", "command").putString("command", cmd.encode())
  }

  private def sessionTimerUpdate(query: JsonObject, update: JsonObject) = {
    findAndModify(query = Some(query), update = Some(update), fields = List("sessionTimer"))
  }

  private def findAndUpdateSessionTimer(sessionId: String, newTimerId: Long): Future[Long] = {
    val query = json.putString("sessionId", sessionId)
    val update = json.putObject("$set", json.putNumber("sessionTimer", newTimerId))

    sendToPersistor(sessionTimerUpdate(query, update)) map checkMongoErrors map { reply =>
      Option(reply.getObject("result").getObject("value")) match {
        case None => throw new SessionException("UNKNOWN_SESSIONID", s"The session with id '${sessionId}' could not be found")
        case Some(obj) => obj.getLong("sessionTimer")
      }
    }
  }

  override def resetTimer(sessionId: String, newTimerId: Long): Future[Long] = {
    findAndUpdateSessionTimer(sessionId, newTimerId)
  }

  override def startSession(): Future[String] = {
    val sessionId = UUID.randomUUID.toString
    val timerId = sm.createTimer(sessionId)
    sendToPersistor(mongoAction("save")
      .putObject("document", json
        .putString("sessionId", sessionId)
        .putNumber("sessionTimer", timerId))) map checkMongoErrors transform ({
      case _ => sessionId
    }, {
      case error =>
        sm.cancelTimer(timerId)
        error
    })
  }

  private def sendToPersistor(obj: JsonObject): Future[Message[JsonObject]] = {
    val p = Promise[Message[JsonObject]]
    vertx.eventBus.send(address, obj, fnToHandler({ (msg: Message[JsonObject]) =>
      p.success(msg)
    }))
    p.future
  }

  private def mongoAction(action: String) =
    json.putString("action", action).putString("collection", collection)

  private def toDataNotation(data: JsonObject) = {
    import scala.collection.JavaConversions._
    val newData = json
    for (fieldName <- data.getFieldNames) {
      val dataField = "data." + fieldName
      Option[Any](data.getField(fieldName)) match {
        case Some(x: Array[Byte]) => newData.putBinary(dataField, x)
        case Some(x: java.lang.Boolean) => newData.putBoolean(dataField, x)
        case Some(x: JsonElement) => newData.putElement(dataField, x)
        case Some(x: Number) => newData.putNumber(dataField, x)
        case Some(x: String) => newData.putString(dataField, x)
        case None =>
        case Some(unknownType) => logger.warn("unknown type of field '" + fieldName + "': " + unknownType)
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

}