package com.campudus.vertx.sessionmanager

import java.util.UUID
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.Handler
import org.vertx.java.core.Vertx
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.json.JsonObject
import scala.concurrent.Future

class SharedDataSessionStore(sm: SessionManager, sharedSessionsName: String, sharedSessionTimeoutsName: String) extends SessionManagerSessionStore with VertxScalaHelpers {

  val vertx = sm.getVertx()
  val sharedSessions = vertx.sharedData.getMap[String, String](sharedSessionsName)
  val sharedSessionTimeouts = vertx.sharedData.getMap[String, String](sharedSessionTimeoutsName)

  override def clearAllSessions(): Future[Boolean] = {
    import scala.collection.JavaConversions._
    for ((sessionId, sessionString) <- sharedSessions) {
      sm.destroySession(sessionId, None)
    }
    Future.successful(true)
  }

  override def getMatches(data: JsonObject): Future[JsonArray] = {
    import scala.collection.JavaConversions._
    val sessionsArray = new JsonArray()
    for ((id, sessionString) <- sharedSessions) {
      val sessionData = new JsonObject(sessionString)
      if (sessionData.copy.mergeIn(data) == sessionData) {
        sessionsArray.addObject(sessionData.putString("sessionId", id))
      }
    }
    Future.successful(sessionsArray)
  }

  override def getOpenSessions(): Future[Long] = Future.successful(sharedSessions.size)

  override def getSessionData(sessionId: String, fields: JsonArray): Future[JsonObject] = sharedSessions.get(sessionId) match {
    case null =>
      Future.failed(new SessionException("SESSION_GONE", "Cannot get data from session '" + sessionId + "'. Session is gone."))
    case sessionString =>
      // session is still in use, change timeout
      val session = new JsonObject(sessionString)
      val result = new JsonObject
      for (key <- fields.toArray if key.isInstanceOf[String]) {
        result.putValue(key.toString, session.getField(key.toString))
      }
      Future.successful(new JsonObject().putObject("data", result))
  }

  override def putSession(sessionId: String, data: JsonObject): Future[Boolean] = {
    sharedSessions.get(sessionId) match {
      case null =>
        Future.failed(new SessionException("SESSION_GONE", "Could not find session with id '" + sessionId + "'."))
      case sessionString =>
        val session = new JsonObject(sessionString)
        session.mergeIn(data)
        sharedSessions.put(sessionId, session.encode)
        Future.successful(true)
    }
  }

  override def removeSession(sessionId: String, timerId: Option[Long]): Future[JsonObject] = {
    val (timerRemoved, actualTimerId) = timerId match {
      case Some(id) => (sharedSessionTimeouts.remove(sessionId, id.toString), id)
      case None => (false, sharedSessionTimeouts.remove(sessionId).toLong)
    }

    Future.successful(json.putNumber("sessionTimer", actualTimerId)
      .putBoolean("timerRemoved", timerRemoved)
      .putObject("session", new JsonObject(sharedSessions.remove(sessionId))))
  }

  override def resetTimer(sessionId: String, newTimerId: Long): Future[Long] = sharedSessionTimeouts.get(sessionId) match {
    case null =>
      Future.failed(new SessionException("UNKNOWN_SESSIONID", "Could not find session with id '" + sessionId + "'."))
    case timerId =>
      if (sharedSessionTimeouts.replace(sessionId, timerId, newTimerId.toString)) {
        Future.successful(timerId.toLong)
      } else {
        Future.failed(new SessionException("TIMER_EXPIRED", "Could not reset timer for session with id '" + sessionId + "'."))
      }
  }

  override def startSession(): Future[String] = {
    val sessionId = UUID.randomUUID.toString
    sharedSessions.putIfAbsent(sessionId, "{}") match {
      case null =>
        sharedSessionTimeouts.put(sessionId, sm.createTimer(sessionId).toString)
        // There is no session with this uuid -> return it
        Future.successful(sessionId)
      case anotherSessionId =>
        // There was a session with this uuid -> create a new one
        startSession()
    }
  }
}