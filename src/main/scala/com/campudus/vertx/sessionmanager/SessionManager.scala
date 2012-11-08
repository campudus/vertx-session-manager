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

import java.util.concurrent.ConcurrentMap
import java.util.UUID
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.Handler
import org.vertx.java.deploy.Verticle
import org.vertx.java.core.json.JsonArray

/**
 * Session Manager Module for Vert.x
 * <p>Please see README.md for full documentation.
 * @author <a href="http://www.campudus.com/">Joern Bernhardt</a>
 */
class SessionManager extends Verticle with Handler[Message[JsonObject]] {

  private val defaultAddress = "campudus.session"
  private val defaultTimeout: Long = 30 * 60 * 1000 // 30 minutes
  private val defaultSessionClientPrefix = "campudus.session."
  private var sharedSessions: ConcurrentMap[String, String] = null
  private var sharedSessionTimeouts: ConcurrentMap[String, Long] = null
  private var configTimeout = defaultTimeout
  private var cleanupAddress: String = null
  private var sessionClientPrefix: String = null

  override def start() {
    val config = container.getConfig()
    vertx.eventBus().registerHandler(config.getString("address", defaultAddress), SessionManager.this)
    val timeout = config.getNumber("timeout");
    configTimeout = if (timeout != null) timeout.longValue else configTimeout
    cleanupAddress = config.getString("cleaner")
    sessionClientPrefix = config.getString("prefix", defaultSessionClientPrefix)

    sharedSessions = vertx.sharedData.getMap(config.getString("map-sessions", "com.campudus.vertx.sessionmanager.sessions"))
    sharedSessionTimeouts = vertx.sharedData.getMap(config.getString("map-timeouts", "com.campudus.vertx.sessionmanager.timeouts"))
  }

  private def createTimer(sessionId: String, timeout: Long) = {
    vertx.setTimer(timeout, new Handler[java.lang.Long]() {
      def handle(timerId: java.lang.Long) {
        destroySession(sessionId, "SESSION_TIMEOUT")
      }
    })
  }

  private def resetTimer(sessionId: String) = {
    if (vertx.cancelTimer(sharedSessionTimeouts.get(sessionId))) {
      sharedSessionTimeouts.put(sessionId, createTimer(sessionId, configTimeout))
      true
    } else {
      false
    }
  }

  private def destroySession(sessionId: String, cause: String) {
    vertx.eventBus.send(sessionClientPrefix + sessionId, new JsonObject().putString("status", "error").putString("error", cause));
    if (cleanupAddress != null) {
      val sessionString = sharedSessions.remove(sessionId)
      vertx.eventBus.send(cleanupAddress, new JsonObject().putString("sessionId", sessionId)
        .putObject("session", new JsonObject(sessionString)))
    } else {
      sharedSessions.remove(sessionId)
    }
    vertx.cancelTimer(sharedSessionTimeouts.remove(sessionId))
  }

  private def getSession(sessionId: String) = {
    sharedSessions.get(sessionId) match {
      case null => null
      case session =>
        // session is still in use, change timeout
        resetTimer(sessionId)
        session
    }
  }

  override def handle(message: Message[JsonObject]) {
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

          getSession(sessionId) match {
            case null =>
              message.reply(raiseJsonError(sessionId, "SESSION_GONE", "Cannot get data from session '" + sessionId + "'. Session is gone."))
            case sessionString =>
              val session = new JsonObject(sessionString)
              val result = new JsonObject
              for (key <- fields.toArray) {
                session.getField(key.toString) match {
                  case null => container.getLogger.warn("field " + key + " is null") // Do not put into result
                  case elem: JsonArray => result.putArray(key.toString, elem)
                  case elem: Array[Byte] => result.putBinary(key.toString, elem)
                  case elem: java.lang.Boolean => result.putBoolean(key.toString, elem)
                  case elem: Number => result.putNumber(key.toString, elem)
                  case elem: JsonObject => result.putObject(key.toString, elem)
                  case elem: String => result.putString(key.toString, elem)
                  case unknownType => container.getLogger.warn("Unknown type: " + null)
                }
              }
              message.reply(new JsonObject().putObject("data", result))
          }
        case unknownSessionIdType => message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot get data from session: 'sessionId' has to be a String."))
      }

      case "put" => message.body.getField("sessionId") match {
        case null => message.reply(createJsonError("SESSIONID_MISSING", "Cannot put data in session: sessionId parameter is missing."))
        case sessionId: String => message.body.getField("data") match {
          case null => message.reply(createJsonError("DATA_MISSING", "Cannot put data in session '" + sessionId + "': data is missing."))
          case data: JsonObject =>
            getSession(sessionId) match {
              case null => message.reply(raiseJsonError(sessionId, "SESSION_GONE", "Cannot put data in session '" + sessionId + "': Session is gone."))
              case sessionString =>
                val session = new JsonObject(sessionString)
                session.mergeIn(data)
                sharedSessions.put(sessionId, session.encode)

                message.reply(new JsonObject().putBoolean("sessionSaved", true))
            }
          case unknownType =>
            message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot put data in session: 'data' has to be a JsonObject."))
        }
        case unknownSessionIdType =>
          message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot put data in session: 'sessionId' has to be a String."))
      }

      case "start" =>
        val sessionId = startSession()
        sharedSessionTimeouts.put(sessionId, createTimer(sessionId, configTimeout))
        message.reply(new JsonObject().putString("sessionId", sessionId))

      case "heartbeat" =>
        message.body.getField("sessionId") match {
          case null => message.reply(createJsonError("SESSIONID_MISSING", "No usable heartbeat: sessionId missing!"))
          case sessionId: String =>
            if (resetTimer(sessionId)) {
              message.reply(new JsonObject().putNumber("timeout", configTimeout))
            } else {
              message.reply(createJsonError("UNKNOWN_SESSIONID", "Heartbeat failed - no timeout/session found with sessionId " + sessionId))
            }
          case unknownSessionIdType =>
            message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot send heartbeat: 'sessionId' has to be a String."))
        }

      case "destroy" =>
        message.body.getField("sessionId") match {
          case null => message.reply(createJsonError("SESSIONID_MISSING", "Cannot destroy session, sessionId missing!"))
          case sessionId: String =>
            destroySession(sessionId, "SESSION_KILL")
            message.reply(new JsonObject().putBoolean("sessionDestroyed", true))
          case unknownSessionIdType =>
            message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot destroy session: 'sessionId' has to be a String."))
        }

      case "status" =>
        import scala.collection.JavaConversions._
        // TODO more reports for the administrator
        message.body.getString("report") match {
          case "connections" =>
            message.reply(new JsonObject().putNumber("openSessions", sharedSessions.size))
          case "matches" =>
            message.body.getField("data") match {
              case null =>
                message.reply(createJsonError("DATA_MISSING", "You have to specify 'data' as a JsonObject to match the sessions on."))
              case data: JsonObject =>
                val sessionsArray = new JsonArray()
                for ((id, sessionString) <- sharedSessions) {
                  val sessionData = new JsonObject(sessionString)
                  if (sessionData.copy.mergeIn(data) == sessionData) {
                    sessionsArray.addObject(sessionData.putString("sessionId", id))
                  }
                }
                message.reply(new JsonObject().putBoolean("matches", sessionsArray.size > 0).putArray("sessions", sessionsArray))
              case unknownDataType =>
                message.reply(createJsonError("WRONG_DATA_TYPE", "Cannot match on data: 'data' has to be a JsonObject."))
            }
          case unknown =>
            message.reply(createJsonError("UNKNOWN_REPORT_REQUEST", "You have to specify the field 'report' as a String with a recognized report option."))
        }

      case "clear" =>
        import scala.collection.JavaConversions._
        for ((sessionId, sessionString) <- sharedSessions) {
          destroySession(sessionId, "SESSION_KILL")
        }
        message.reply(new JsonObject().putBoolean("cleared", true))

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

  private def startSession(): String = {
    val uuid = UUID.randomUUID.toString
    sharedSessions.putIfAbsent(uuid, new JsonObject().encode) match {
      case null =>
        sharedSessionTimeouts.put(uuid, configTimeout)
        // There is no session with this uuid -> return it
        uuid
      case sessionId =>
        // There was a session with this uuid -> create a new one
        startSession
    }
  }

}
