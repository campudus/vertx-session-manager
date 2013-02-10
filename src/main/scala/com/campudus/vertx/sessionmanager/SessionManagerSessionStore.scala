package com.campudus.vertx.sessionmanager

import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.Vertx
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.AsyncResultHandler

abstract class SessionManagerSessionStore {

  def clearAllSessions(resultHandler: AsyncResultHandler[Boolean]): Unit

  def getOpenSessions(resultHandler: AsyncResultHandler[Long]): Unit

  def getMatches(data: JsonObject, resultHandler: AsyncResultHandler[JsonArray]): Unit

  def getSessionData(sessionId: String, fields: JsonArray, resultHandler: AsyncResultHandler[JsonObject]): Unit

  def putSession(sessionId: String, session: JsonObject, resultHandler: AsyncResultHandler[Boolean]): Unit

  def removeSession(sessionId: String, timerId: Option[Long], resultHandler: AsyncResultHandler[JsonObject]): Unit

  def resetTimer(sessionId: String, newTimerId: Long, resultHandler: AsyncResultHandler[Long]): Unit

  def startSession(resultHandler: AsyncResultHandler[String]): Unit

}