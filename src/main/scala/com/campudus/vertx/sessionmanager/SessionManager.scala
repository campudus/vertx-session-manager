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

import org.vertx.java.core.AsyncResult
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.Handler
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.json.JsonObject
import org.vertx.java.deploy.Verticle

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
  private var cleanupAddress: String = null
  private var sessionClientPrefix: String = null
  private var mongoCollection: JsonObject = null

  private var sessionStore: SessionManagerDatabase = null

  override def start() {
    val config = container.getConfig()
    vertx.eventBus().registerHandler(config.getString("address", defaultAddress), SessionManager.this)
    val timeout = config.getNumber("timeout");
    configTimeout = if (timeout != null) timeout.longValue else configTimeout
    cleanupAddress = config.getString("cleaner")
    sessionClientPrefix = config.getString("prefix", defaultSessionClientPrefix)
    mongoCollection = config.getObject("mongo-sessions", null)

    if (mongoCollection == null) {
      sessionStore = new SharedDataSessionStore(vertx, this, config.getString("map-sessions", "com.campudus.vertx.sessionmanager.sessions"), config.getString("map-timeouts", "com.campudus.vertx.sessionmanager.timeouts"))
    } else {
      throw new Exception("mongodb not implemented yet")
    }

  }

  private def replyMessage[T](success: JsonObject)(implicit message: Message[JsonObject], result: AsyncResult[T]): Unit = replyMessage(success, json)(message, result)
  private def replyMessage[T](success: JsonObject, fail: JsonObject)(implicit message: Message[JsonObject], result: AsyncResult[T]): Unit = if (result.succeeded()) {
    message.reply(success)
  } else {
    result.exception match {
      case e: SessionException =>
        message.reply(fail.putString("status", "error").putString("error", e.errorId).putString("message", result.exception.getMessage()))
      case e =>
        message.reply(fail.putString("status", "error").putString("error", "SESSIONSTORE_ERROR").putString("message", result.exception.getMessage()))
    }
  }

  def createTimer(sessionId: String) = {
    vertx.setTimer(configTimeout, new Handler[java.lang.Long]() {
      def handle(timerId: java.lang.Long) {
        destroySession(sessionId, "SESSION_TIMEOUT")
      }
    })
  }

  def destroySession(sessionId: String, cause: String = "SESSION_KILL", resultHandler: AsyncResultHandler[JsonObject] = null) {
    vertx.eventBus.send(sessionClientPrefix + sessionId, json.putString("status", "error").putString("error", cause).putString("message", "This session was killed."));
    sessionStore.removeSession(sessionId, {
      res: AsyncResult[JsonObject] =>
        if (res.succeeded() && cleanupAddress != null) {
          vertx.eventBus.send(cleanupAddress, json.putString("sessionId", sessionId)
            .putObject("session", res.result))
        } else {
          container.getLogger().warn("Could not remove session " + sessionId, res.exception)
        }

        if (resultHandler != null) {
          resultHandler.handle(res)
        }
    })
  }

  override def handle(msg: Message[JsonObject]) {
    implicit val message = msg
    message.body.getField("action") match {
      case "get" => message.body.getField("sessionId") match {
        case null => message.reply(createJsonError("SESSIONID_MISSING", "Cannot get data from session: sessionId parameter is missing."))
        case sessionId: String =>
          val fields = message.body.getField("fields") match {
            case null =>
              message.reply(createJsonError("FIELDS_MISSING", "Cannot get data from session '" + sessionId + "': fields parameter is missing."))
              return // Error!
            case fieldsObj => fieldsObj match {
              case list: JsonArray => list
              case obj => new JsonArray().addString(obj.toString)
            }
          }

          sessionStore.getSessionData(sessionId, fields, {
            implicit res: AsyncResult[JsonObject] =>
              replyMessage(res.result)
          })
        case unknownSessionIdType => message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot get data from session: 'sessionId' has to be a String."))
      }

      case "put" => message.body.getField("sessionId") match {
        case null => message.reply(createJsonError("SESSIONID_MISSING", "Cannot put data in session: sessionId parameter is missing."))
        case sessionId: String => message.body.getField("data") match {
          case null => message.reply(createJsonError("DATA_MISSING", "Cannot put data in session '" + sessionId + "': data is missing."))
          case data: JsonObject =>
            sessionStore.putSession(sessionId, data, {
              implicit res: AsyncResult[Boolean] =>
                replyMessage(json.putBoolean("sessionSaved", true))
            })
          case unknownType =>
            message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot put data in session: 'data' has to be a JsonObject."))
        }
        case unknownSessionIdType =>
          message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot put data in session: 'sessionId' has to be a String."))
      }

      case "start" =>
        sessionStore.startSession({ implicit res: AsyncResult[String] =>
          replyMessage(json.putString("sessionId", res.result))
        })

      case "heartbeat" =>
        message.body.getField("sessionId") match {
          case null => message.reply(createJsonError("SESSIONID_MISSING", "No usable heartbeat: sessionId missing!"))
          case sessionId: String =>
            sessionStore.resetTimer(sessionId, {
              implicit res: AsyncResult[Boolean] =>
                replyMessage(json.putNumber("timeout", configTimeout))
            })
          case unknownSessionIdType =>
            message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot send heartbeat: 'sessionId' has to be a String."))
        }

      case "destroy" =>
        message.body.getField("sessionId") match {
          case null => message.reply(createJsonError("SESSIONID_MISSING", "Cannot destroy session, sessionId missing!"))
          case sessionId: String =>
            destroySession(sessionId, "SESSION_KILL", { implicit result: AsyncResult[JsonObject] =>
              replyMessage(json.putBoolean("sessionDestroyed", true), json.putBoolean("sessionDestroyed", false))
            })
          case unknownSessionIdType =>
            message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot destroy session: 'sessionId' has to be a String."))
        }

      case "status" =>
        // TODO more reports for the administrator
        message.body.getString("report") match {
          case "connections" =>
            sessionStore.getOpenSessions({ implicit res: AsyncResult[Long] =>
              replyMessage(json.putNumber("openSessions", res.result))
            })
          case "matches" =>
            message.body.getField("data") match {
              case null =>
                message.reply(createJsonError("DATA_MISSING", "You have to specify 'data' as a JsonObject to match the sessions on."))
              case data: JsonObject =>
                sessionStore.getMatches(data, { implicit result: AsyncResult[JsonArray] =>
                  replyMessage(json.putBoolean("matches", result.result.size > 0).putArray("sessions", result.result))
                })
              case unknownDataType =>
                message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot match on data: 'data' has to be a JsonObject."))
            }
          case unknown =>
            message.reply(createJsonError("UNKNOWN_REPORT_REQUEST", "You have to specify the field 'report' as a String with a recognized report option."))
        }

      case "clear" =>
        sessionStore.clearAllSessions(fnToAsyncHandler({ implicit result: AsyncResult[Boolean] =>
          replyMessage(json.putBoolean("cleared", true), json.putBoolean("cleared", false))
        }))

      case unknown => message.reply(createJsonError("UNKNOWN_COMMAND", "Session manager does not understand action '" + unknown + "'."))
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
