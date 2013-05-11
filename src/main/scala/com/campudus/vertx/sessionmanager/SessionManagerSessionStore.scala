package com.campudus.vertx.sessionmanager

import scala.concurrent.Future

import org.vertx.java.core.json.{ JsonArray, JsonObject }

abstract class SessionManagerSessionStore {

  def clearAllSessions(): Future[Boolean]

  def getOpenSessions(): Future[Long]

  def getMatches(data: JsonObject): Future[JsonArray]

  def getSessionData(sessionId: String, fields: JsonArray): Future[JsonObject]

  def putSession(sessionId: String, session: JsonObject): Future[Boolean]

  def removeSession(sessionId: String, timerId: Option[Long]): Future[JsonObject]

  def resetTimer(sessionId: String, newTimerId: Long): Future[Long]

  def startSession(): Future[String]

}