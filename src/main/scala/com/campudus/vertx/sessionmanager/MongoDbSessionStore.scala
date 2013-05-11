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

  private def checkMongoErrors(mongoReply: Message[JsonObject]): Future[JsonObject] = {
    mongoReply.body.getString("status") match {
      case "ok" => Future.successful(mongoReply.body)
      case _ =>
        val ex = new SessionException("MONGODB_ERROR", mongoReply.body().getString("message"))
        logger.warn("Session error: " + ex)
        Future.failed(ex)
    }
  }

  // TODO use findAndModify as soon as available
  override def clearAllSessions(): Future[Boolean] = {
    import scala.collection.JavaConversions._
    sendToPersistor(mongoAction("find").putObject("matcher", json)) flatMap checkMongoErrors flatMap { reply =>
      val results = reply.getArray("results")
      for (result <- results) {
        val res = result.asInstanceOf[JsonObject]
        sm.clearSession(res.getString("sessionId"), res.getNumber("sessionTimer").longValue(), res.getObject("data"))
      }
      sendToPersistor(mongoAction("delete").putObject("matcher", json)) flatMap checkMongoErrors map (_ => true)
    }
  }

  override def getMatches(data: JsonObject): Future[JsonArray] = {
    sendToPersistor(mongoAction("find").putObject("matcher", toDataNotation(data))) flatMap checkMongoErrors map (
      obj => obj.getArray("results"))
  }

  override def getOpenSessions(): Future[Long] = {
    sendToPersistor(mongoAction("count")) flatMap checkMongoErrors map (obj => obj.getNumber("count").longValue)
  }

  override def getSessionData(sessionId: String, fields: JsonArray): Future[JsonObject] = {
    val searchFor = json.putString("sessionId", sessionId)
    val action = mongoAction("findone").putObject("matcher", searchFor).
      putObject("keys", jsonArrayToFieldSelection(fields))
    sendToPersistor(action) flatMap checkMongoErrors flatMap { result =>
      Option(result.getObject("result")) match {
        case None =>
          Future.failed(SessionException.gone())
        case Some(foundSession) =>
          Future.successful(foundSession)
      }
    }
  }

  // TODO find and update with findAndModify when available
  override def putSession(sessionId: String, data: JsonObject): Future[Boolean] = {
    val searchFor = json.putString("sessionId", sessionId)
    sendToPersistor(mongoAction("findone").putObject("matcher", searchFor)) flatMap checkMongoErrors flatMap { result =>
      result.getObject("result") match {
        case obj: JsonObject =>
          sendToPersistor(
            mongoAction("update")
              .putObject("criteria", searchFor)
              .putObject("objNew", json.putObject("$set", toDataNotation(data)))) flatMap checkMongoErrors map (_ => true)
        case _ =>
          Future.failed(SessionException.gone())
      }
    }
  }

  override def removeSession(sessionId: String, timerId: Option[Long]): Future[JsonObject] = {
    val searchFor = json.putString("sessionId", sessionId)

    sendToPersistor(mongoAction("findone").
      putObject("matcher", searchFor).
      putObject("keys", json
        .putBoolean("data", true)
        .putBoolean("sessionTimer", true))) flatMap checkMongoErrors flatMap { findResult =>
      Option(findResult.getObject("result")) match {
        case None =>
          Future.failed(SessionException.gone())
        case Some(obj) =>
          // timerId does not matter since it is saved together with the session
          sendToPersistor(mongoAction("delete").putObject("matcher", searchFor)) flatMap checkMongoErrors map {
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

    cmd.encode
  }

  private def sessionTimerUpdate(query: JsonObject, update: JsonObject) = {
    val cmd = findAndModify(query = Some(query), update = Some(update), fields = List("sessionTimer"))

    println("cmd: " + cmd)
    json.putString("action", "command").putString("command", cmd)
  }

  private def findAndUpdateSessionTimer(sessionId: String, newTimerId: Long): Future[Long] = {
    val query = json.putString("sessionId", sessionId)
    val update = json.putObject("$set", json.putNumber("sessionTimer", newTimerId))

    sendToPersistor(sessionTimerUpdate(query, update)) flatMap checkMongoErrors map { reply =>
      val oldTimerId = reply.getObject("result").getObject("value").getLong("sessionTimer")
      println("updated timer " + oldTimerId + " to " + newTimerId + " in session " + sessionId)
      oldTimerId
    }
  }

  override def resetTimer(sessionId: String, newTimerId: Long): Future[Long] = {
    findAndUpdateSessionTimer(sessionId, newTimerId)
    //
    //    val searchFor = json.putString("sessionId", sessionId)
    //
    //    sendToPersistor(mongoAction("findone").
    //      putObject("matcher", searchFor).
    //      putObject("keys", json.putBoolean("sessionTimer", true))) flatMap checkMongoErrors flatMap { result =>
    //      Option(result.getObject("result")) match {
    //        case None =>
    //          Future.failed(new SessionException("UNKNOWN_SESSIONID", "Could not find session with id '" + sessionId + "'."))
    //        case Some(foundSession) =>
    //          val oldTimerId = foundSession.getLong("sessionTimer")
    //          val replaceObj = searchFor.putNumber("sessionTimer", oldTimerId)
    //
    //          sendToPersistor(mongoAction("update")
    //            .putObject("criteria", replaceObj)
    //            .putObject("objNew", json.putObject("$set",
    //              json.putNumber("sessionTimer", newTimerId)))) flatMap checkMongoErrors map { obj =>
    //            println("updated timer " + oldTimerId + " to " + newTimerId + "?!? session " + sessionId)
    //
    //            oldTimerId
    //          }
    //      }
    //    }
  }

  // TODO use findAndModify to create a new session, if none available with this sessionId
  override def startSession(): Future[String] = {
    val sessionId = UUID.randomUUID.toString
    val timerId = sm.createTimer(sessionId)
    sendToPersistor(mongoAction("save")
      .putObject("document", json
        .putString("sessionId", sessionId)
        .putNumber("sessionTimer", timerId))) flatMap checkMongoErrors transform ({
      case _ => sessionId
    }, {
      case error =>
        sm.cancelTimer(timerId)
        error
    })
  }

  private def sendToPersistor(obj: JsonObject): Future[Message[JsonObject]] = {
    val p = Promise[Message[JsonObject]]
    println("send to persistor: " + obj.encode())
    vertx.eventBus.send(address, obj, fnToHandler({ (msg: Message[JsonObject]) =>
      println("msg from persistor: " + msg.body.encode)
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