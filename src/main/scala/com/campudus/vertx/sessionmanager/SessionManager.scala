/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.campudus.vertx.sessionmanager

import org.vertx.java.core.{ AsyncResult, AsyncResultHandler, Handler }
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.{ JsonArray, JsonObject }
import scala.concurrent.Future
import com.campudus.vertx.Verticle
import scala.util.Success
import scala.util.Failure
import scala.util.Try

/**
 * Session Manager Module for Vert.x
 * <p>Please see README.md for full documentation.
 * @author <a href="http://www.campudus.com/">Joern Bernhardt</a>
 * @author <a href="http://www.campudus.com/">Maximilian Stemplinger</a>
 */
class SessionManager extends Verticle with Handler[Message[JsonObject]] with VertxScalaHelpers {

  private val defaultAddress = "campudus.session"
  private val defaultTimeout: Long = 30 * 60 * 1000 // 30 minutes
  private val defaultSessionClientPrefix = "campudus.session."
  private var configTimeout = defaultTimeout
  private var cleanupAddress: Option[String] = None
  private var sessionClientPrefix: String = null
  private var mongoCollection: Option[JsonObject] = None

  private var sessionStore: SessionManagerSessionStore = null

  override def start() {
    val config = container.config
    vertx.eventBus().registerHandler(config.getString("address", defaultAddress), SessionManager.this)
    val timeout = config.getNumber("timeout");
    configTimeout = if (timeout != null) timeout.longValue else configTimeout
    cleanupAddress = Option(config.getString("cleaner"))
    sessionClientPrefix = config.getString("prefix", defaultSessionClientPrefix)
    mongoCollection = Option(config.getObject("mongo-sessions"))

    mongoCollection match {
      case Some(conf) =>
        val address = conf.getString("address")
        if (address == null) {
          throw new SessionException("CONFIGURATION_ERROR", "MongoDB persistor address missing. Please provide 'address' field in 'mongo-session'.")
        }
        val collection = conf.getString("collection")
        if (collection == null) {
          throw new SessionException("CONFIGURATION_ERROR", "MongoDB collection name missing. Please provide 'collection' field in 'mongo-session'.")
        }
        sessionStore = new MongoDbSessionStore(this, address, collection)
      case None =>
        sessionStore = new SharedDataSessionStore(this, config.getString("map-sessions", "com.campudus.vertx.sessionmanager.sessions"), config.getString("map-timeouts", "com.campudus.vertx.sessionmanager.timeouts"))
    }

  }

  private def replyOk(success: JsonObject)(implicit message: Message[JsonObject]): Unit = {
    val body = success.putString("status", "ok")
    message.reply(body)
  }
  private def replyError(error: Throwable)(implicit message: Message[JsonObject]): Unit = {
    val errorId = error match {
      case sessionException: SessionException => sessionException.errorId
      case _ => "SESSIONSTORE_ERROR"
    }
    replyError(errorId, error.getMessage)
  }
  private def replyError(id: String, message: String)(implicit msg: Message[JsonObject]): Unit = {
    val body = json.putString("status", "error").putString("error", id).putString("message", message)
    msg.reply(body)
  }
  private def replyResult(res: Try[JsonObject])(implicit message: Message[JsonObject]): Unit = res match {
    case Success(result) => replyOk(result)
    case Failure(error) => replyError(error)
  }
  private def replyResultWithHeartbeat(sessionId: String)(res: Try[JsonObject])(implicit message: Message[JsonObject]): Unit = {
    res match {
      case Success(result) =>
        heartBeat(sessionId) onComplete { case _ => replyOk(result) }
      case Failure(error) =>
        replyError(error)
    }
  }

  def createTimer(sessionId: String) = {
    val timerId = vertx.setTimer(configTimeout, new Handler[java.lang.Long]() {
      def handle(timerId: java.lang.Long) {
        destroySession(sessionId, Some(timerId), "SESSION_TIMEOUT")
      }
    })
    timerId
  }

  def cancelTimer(timerId: Long) = {
    vertx.cancelTimer(timerId)
  }

  def resetTimer(sessionId: String): Future[Boolean] = {
    val timerId = createTimer(sessionId)
    sessionStore.resetTimer(sessionId, timerId).transform({ result =>
      cancelTimer(result)
      true
    }, { error =>
      cancelTimer(timerId)
      error
    })
  }

  private def heartBeat(sessionId: String) = resetTimer(sessionId)

  private def tellClientSessionIsKilled(sessionId: String, cause: String) = {
    vertx.eventBus.send(sessionClientPrefix + sessionId, json.putString("status", "error").putString("error", cause).putString("message", "This session was killed."))
  }

  private def cleanUpSession(sessionId: String, session: JsonObject): Unit = {
    cleanupAddress match {
      case Some(address) =>
        vertx.eventBus.send(address, json.putString("sessionId", sessionId).putObject("session", session))
      case None =>
    }
  }

  def clearSession(sessionId: String, sessionTimer: Long, session: JsonObject, cause: String = "SESSION_KILL") {
    cancelTimer(sessionTimer)
    tellClientSessionIsKilled(sessionId, cause)
    cleanUpSession(sessionId, session)
  }

  def destroySession(sessionId: String, timerId: Option[Long], cause: String = "SESSION_KILL"): Future[JsonObject] = {
    tellClientSessionIsKilled(sessionId, cause)
    sessionStore.removeSession(sessionId, timerId) transform ({ result =>
      cancelTimer(result.getNumber("sessionTimer").longValue())
      cleanUpSession(sessionId, result.getObject("session"))
      result
    }, { cause =>
      container.logger().warn("Could not remove session " + sessionId, cause)
      cause
    })
  }

  override def handle(msg: Message[JsonObject]) {
    implicit val message = msg

    Option[Any](message.body.getField("action")) match {
      case Some("get") => Option[Any](message.body.getField("sessionId")) match {
        case None =>
          replyError("SESSIONID_MISSING", "Cannot get data from session: sessionId parameter is missing.")
        case Some(sessionId: String) =>
          val fields = Option[Any](message.body.getField("fields")) match {
            case None =>
              replyError("FIELDS_MISSING", "Cannot get data from session '" + sessionId + "': fields parameter is missing.")
              return // Error!
            case Some(list: JsonArray) => list
            case Some(obj: String) => new JsonArray().addString(obj.toString)
            case Some(unknownType) =>
              replyError("WRONG_DATA_TYPE", "Cannot get data from session: 'fields' has to be a String or a JsonArray containing Strings.")
              return
          }
          sessionStore.getSessionData(sessionId, fields) onComplete replyResultWithHeartbeat(sessionId)
        case Some(unknownType) =>
          replyError("WRONG_DATA_TYPE", "Cannot get data from session: 'sessionId' has to be a String.")
      }

      case Some("put") => Option[Any](message.body.getField("sessionId")) match {
        case None =>
          replyError("SESSIONID_MISSING", "Cannot put data in session: sessionId parameter is missing.")
        case Some(sessionId: String) => Option[Any](message.body.getField("data")) match {
          case None =>
            replyError("DATA_MISSING", "Cannot put data in session '" + sessionId + "': data is missing.")
          case Some(data: JsonObject) =>
            sessionStore.putSession(sessionId, data) map { res =>
              json.putBoolean("sessionSaved", res)
            } onComplete replyResultWithHeartbeat(sessionId)
          case Some(unknownType) =>
            replyError("WRONG_DATA_TYPE", "Cannot put data in session: 'data' has to be a JsonObject.")
        }
        case Some(unknownSessionIdType) =>
          replyError("WRONG_DATA_TYPE", "Cannot put data in session: 'sessionId' has to be a String.")
      }

      case Some("start") =>
        sessionStore.startSession() map (json.putString("sessionId", _)) onComplete replyResult

      case Some("heartbeat") =>
        Option[Any](message.body.getField("sessionId")) match {
          case None => replyError("SESSIONID_MISSING", "No usable heartbeat: sessionId missing!")
          case Some(sessionId: String) =>
            heartBeat(sessionId) map { ignore =>
              json.putNumber("timeout", configTimeout)
            } onComplete replyResult
          case Some(unknownSessionIdType) =>
            replyError("WRONG_DATA_TYPE", "Cannot send heartbeat: 'sessionId' has to be a String.")
        }

      case Some("destroy") =>
        Option[Any](message.body.getField("sessionId")) match {
          case None => replyError("SESSIONID_MISSING", "Cannot destroy session, sessionId missing!")
          case Some(sessionId: String) =>
            destroySession(sessionId, None, "SESSION_KILL") map { result =>
              json.putBoolean("sessionDestroyed", true)
            } onComplete replyResult
          case Some(unknownSessionIdType) =>
            replyError("WRONG_DATA_TYPE", "Cannot destroy session: 'sessionId' has to be a String.")
        }

      case Some("status") =>
        // TODO more reports for the administrator
        Option[Any](message.body.getField("report")) match {
          case Some("connections") =>
            sessionStore.getOpenSessions() map { result =>
              json.putNumber("openSessions", result)
            } onComplete replyResult
          case Some("matches") => Option[Any](message.body.getField("data")) match {
            case None =>
              replyError("DATA_MISSING", "You have to specify 'data' as a JsonObject to match the sessions on.")
            case Some(data: JsonObject) =>
              sessionStore.getMatches(data) map { result =>
                json.putBoolean("matches", result.size > 0).putArray("sessions", result)
              } onComplete replyResult
            case Some(unknownDataType) =>
              replyError("WRONG_DATA_TYPE", "Cannot match on data: 'data' has to be a JsonObject.")
          }
          case unknown =>
            replyError("UNKNOWN_REPORT_REQUEST", "You have to specify the field 'report' as a String with a recognized report option.")
        }

      case Some("clear") =>
        sessionStore.clearAllSessions() map { _ =>
          json.putBoolean("cleared", true)
        } recover {
          case _ => json.putBoolean("cleared", false)
        } onComplete replyResult
      case Some(unknown) => replyError("UNKNOWN_COMMAND", "Session manager does not understand action '" + unknown + "'.")
      case None => replyError("UNKNOWN_COMMAND", "There was no 'action' parameter set.")
    }
  }

  private def createJsonError(error: String, message: String) = {
    new JsonObject().putString("status", "error").putString("error", error).putString("message", message)
  }

  private def raiseJsonError(sessionId: String, error: String, message: String) = {
    val errorJson = createJsonError(error, message)
    vertx.eventBus.send(sessionClientPrefix + sessionId, errorJson)
    errorJson
  }

}

object SessionManager {
  val defaultAddress = "campudus.session"
  val defaultTimeout: Long = 30 * 60 * 1000 // 30 minutes
  val defaultSessionClientPrefix = "campudus.session."
}
