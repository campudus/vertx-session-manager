package com.campudus.vertx.sessionmanager

import org.vertx.java.core.eventbus.EventBus
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.Handler
import org.vertx.java.framework.TestClientBase
import org.vertx.java.core.json.JsonArray
import scala.actors.threadpool.AtomicInteger
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.AsyncResult

class SessionManagerTestClient extends TestClientBase {

  val smAddress = "sessions"

  val someData = new JsonObject()
    .putObject("object1", new JsonObject().putString("key1", "something").putNumber("key2", 15))
    .putObject("object2", new JsonObject().putString("key1", "another thing").putNumber("key2", 16))
    .putString("teststring", "ok")
    .putNumber("answer", 42)

  var eb: EventBus = null
  val sessionClientPrefix: String = "session."
  val cleanupAddress: String = "sessioncleaner"
  val defaultTimeout: Long = 5000
  val noTimeoutTest: Long = 3500
  val timeoutTest: Long = 10000

  override def start() {
    super.start()
    eb = vertx.eventBus()
    val config = new JsonObject()
    config.putString("address", smAddress)
    config.putString("cleaner", cleanupAddress)
    config.putNumber("timeout", defaultTimeout)
    config.putString("prefix", sessionClientPrefix)

    container.deployModule("com.campudus.session-manager-v" + System.getProperty("vertx.version"), config, 1, new Handler[java.lang.String] {
      def handle(res: String) {
        tu.appReady();
      }
    })
  }

  override def stop() {
    super.stop();
  }

  private def continueAfterNoErrorReply(fn: (Message[JsonObject]) => Unit) = new Handler[Message[JsonObject]]() {
    override def handle(msg: Message[JsonObject]) = msg.body.getString("error") match {
      case null => fn(msg)
      case str => tu.azzert(false, "Should not get an error, but got: " + str)
    }
  }

  private def continueAfterErrorReply(error: String)(fn: (Message[JsonObject]) => Unit) = new Handler[Message[JsonObject]]() {
    override def handle(msg: Message[JsonObject]) = msg.body.getString("status") match {
      case null => tu.azzert(false, "Should get an error, but got no error! " + msg.body.encode)
      case "error" =>
        if (error != null) {
          tu.azzert(error == msg.body.getString("error"), error + " does not equal " + msg.body.getString("error"))
        }
        fn(msg)
      case str => tu.azzert(false, "Should get an error status, but got different status: " + str)
    }
  }

  private def afterClearDo(after: => Unit) {
    eb.send(smAddress, new JsonObject().putString("action", "clear"),
      continueAfterNoErrorReply {
        msg =>
          tu.azzert(msg.body.getBoolean("cleared", false))
          after
      })
  }

  private def afterCreateDo(after: String => Unit) {
    val json = new JsonObject().putString("action", "start")

    eb.send(smAddress, json, continueAfterNoErrorReply {
      msg =>
        after(msg.body.getString("sessionId"))
    })
  }

  private def getSessionData(sessionId: String, fields: JsonArray)(after: JsonObject => Unit) {
    eb.send(smAddress, new JsonObject().putString("action", "get")
      .putString("sessionId", sessionId)
      .putArray("fields", fields),
      continueAfterNoErrorReply {
        msgAfterGet =>
          after(msgAfterGet.body.getObject("data"))
      })
  }

  private def afterPutDo(data: JsonObject)(after: String => Unit) {
    afterCreateDo {
      sessionId =>
        eb.send(smAddress,
          new JsonObject().putString("action", "put")
            .putString("sessionId", sessionId)
            .putObject("data", data),
          continueAfterNoErrorReply {
            msgAfterPut =>
              tu.azzert(msgAfterPut.body.getBoolean("sessionSaved", false), "Session should have been saved!")
              after(sessionId)
          })
    }
  }

  private def afterPutDo(after: String => Unit) {
    afterPutDo(someData)(after)
  }

  private def afterPutAndGetDo(after: String => Unit) {
    afterClearDo {
      afterPutDo {
        sessionId =>
          val asynchTests = new AtomicInteger(0)
          val asynchTestCount = 2

          // Get the saved stuff again
          eb.send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId)
              .putArray("fields",
                new JsonArray()
                  .addString("object1")
                  .addString("object2")
                  .addString("teststring")
                  .addString("answer")),
            continueAfterNoErrorReply {
              msgAfterGet =>
                val data = msgAfterGet.body.getObject("data")
                tu.azzert(data != null, "Should receive data, but got null")
                val object1 = data.getObject("object1")
                tu.azzert("something" == object1.getString("key1"), "Should have gotten something out of data, but got " + data.getString("key1"))
                tu.azzert(15 == object1.getNumber("key2").intValue, "Should have gotten 15 out of data, but got " + data.getNumber("key2"))
                val object2 = data.getObject("object2")
                tu.azzert("another thing" == object2.getString("key1"), "Should have gotten another thing out of data, but got " + object2.getString("key1"))
                tu.azzert(16 == object2.getNumber("key2").intValue, "Should have gotten 16 out of data, but got " + object2.getNumber("key2"))
                val object3 = data.getObject("object3")
                tu.azzert(object3 == null, "where did object3 come from?")
                val teststring = data.getString("teststring")
                tu.azzert("ok" == teststring, "Should get a teststring")
                val answer = data.getNumber("answer")
                tu.azzert(answer.intValue == 42, "Should get 42 as answer")

                if (asynchTests.incrementAndGet == asynchTestCount) {
                  after(sessionId)
                }
            })

          // Get saved stuff partially
          eb.send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId)
              .putArray("fields", new JsonArray().addString("object2").addString("teststring")),
            continueAfterNoErrorReply {
              msgAfterGet =>
                val data = msgAfterGet.body.getObject("data")
                tu.azzert(data != null, "Should receive data, but got null")
                val object1 = data.getObject("object1")
                tu.azzert(object1 == null, "object1 should not be in the result!")
                val object2 = data.getObject("object2")
                tu.azzert("another thing" == object2.getString("key1"), "Should have gotten another thing out of data, but got " + object2.getString("key1"))
                tu.azzert(16 == object2.getNumber("key2").intValue, "Should have gotten 16 out of data, but got " + object2.getNumber("key2"))
                val teststring = data.getString("teststring")
                tu.azzert("ok" == teststring, "Should get a teststring")
                val answer = data.getNumber("answer")
                tu.azzert(answer == null, "Should not get the answer")

                if (asynchTests.incrementAndGet == asynchTestCount) {
                  after(sessionId)
                }
            })
      }
    }
  }

  private def afterGetOpenSessionsDo(after: Int => Unit) {
    eb.send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"), continueAfterNoErrorReply {
      msgAfterStatus =>
        after(msgAfterStatus.body.getNumber("openSessions").intValue)
    })
  }

  def testClear() {
    afterClearDo {
      tu.testComplete
    }
  }

  def testCreateSession() {
    afterClearDo {
      afterCreateDo {
        sessionId =>
          tu.testComplete()
      }
    }
  }

  def testPutSession() {
    afterClearDo {
      afterPutDo {
        sessionId =>
          // Provoke an error message because of an invalid sessionId
          eb.send(smAddress,
            new JsonObject().putString("action", "put")
              .putString("sessionId", sessionId + "123")
              .putObject("data", someData),
            continueAfterErrorReply("SESSION_GONE") {
              msgAfterErrorPut =>
                tu.testComplete()
            })
      }
    }
  }

  def testPutAndGetSession() {
    afterClearDo {
      afterPutAndGetDo {
        sessionId =>
          tu.testComplete()
      }
    }
  }

  def testGetFieldsSession() {
    afterClearDo {
      afterPutDo {
        sessionId =>
          val asynchTestCount = 3
          val asynchTests = new AtomicInteger(0)

          // Results in an error
          eb.send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId + "123")
              .putArray("fields", new JsonArray().addString("teststring")),
            continueAfterErrorReply("SESSION_GONE") {
              msgAfterErrorPut =>
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  tu.testComplete
                }
            })

          // Results in an error, field missing
          eb.send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId),
            continueAfterErrorReply("FIELDS_MISSING") {
              msgAfterErrorPut =>
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  tu.testComplete
                }
            })

          // Results in an error, field missing
          eb.send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId)
              .putString("fields", "teststring"),
            continueAfterNoErrorReply {
              msgAfterNoErrorPut =>
                tu.azzert("ok" == msgAfterNoErrorPut.body.getObject("data").getString("teststring"), "Should result in the teststring 'ok'")
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  tu.testComplete
                }
            })
      }
    }
  }

  def testWrongDataTypes() {
    afterPutDo {
      sessionId =>
        val asynchTestCount = 11
        val asynchTests = new AtomicInteger(0)

        def errorTest(json: JsonObject, error: String) = {
          eb.send(smAddress, json,
            continueAfterErrorReply(error) {
              errorMessage =>
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  tu.testComplete
                }
            })
        }

        // wrong action parameter
        errorTest(new JsonObject().putNumber("action", 17), "UNKNOWN_COMMAND")

        // wrong sessionId parameter in get
        errorTest(new JsonObject().putString("action", "get")
          .putNumber("sessionId", 1)
          .putArray("fields", new JsonArray().addString("teststring")),
          "WRONG_DATA_TYPE")

        // wrong sessionId parameter in put
        errorTest(new JsonObject().putString("action", "put")
          .putNumber("sessionId", 1)
          .putArray("fields", new JsonArray().addString("teststring")),
          "WRONG_DATA_TYPE")

        // wrong data parameter in put
        errorTest(new JsonObject().putString("action", "put")
          .putString("sessionId", sessionId)
          .putString("data", "something"),
          "WRONG_DATA_TYPE")

        // wrong sessionId parameter in heartbeat
        errorTest(new JsonObject().putString("action", "heartbeat")
          .putNumber("sessionId", 1),
          "WRONG_DATA_TYPE")

        // wrong sessionId parameter in destroy
        errorTest(new JsonObject().putString("action", "destroy")
          .putNumber("sessionId", 1),
          "WRONG_DATA_TYPE")

        // wrong sessionId parameter in destroy
        errorTest(new JsonObject().putString("action", "destroy")
          .putNumber("sessionId", 1),
          "WRONG_DATA_TYPE")

        // Report missing -> Unknown
        errorTest(new JsonObject().putString("action", "status"),
          "UNKNOWN_REPORT_REQUEST")

        // Report doesnt exist -> Unknown
        errorTest(new JsonObject().putString("action", "status")
          .putString("report", "the_one_that_doesnt_exist"),
          "UNKNOWN_REPORT_REQUEST")

        // matches report needs a data parameter
        errorTest(new JsonObject().putString("action", "status")
          .putString("report", "matches"),
          "DATA_MISSING")

        // matches report needs data parameter as JsonObject
        errorTest(new JsonObject().putString("action", "status")
          .putString("report", "matches")
          .putNumber("data", 15),
          "WRONG_DATA_TYPE")
    }
  }

  def testOverwriteSessionData() {
    afterClearDo {
      afterPutAndGetDo {
        sessionId =>
          eb.send(smAddress, new JsonObject().putString("action", "put")
            .putString("sessionId", sessionId)
            .putObject("data", new JsonObject()
              .putObject("object1", new JsonObject().putBoolean("overwritten", true))),
            continueAfterNoErrorReply {
              msgAfterOverride =>
                eb.send(smAddress, new JsonObject().putString("action", "get")
                  .putString("sessionId", sessionId)
                  .putArray("fields", new JsonArray().addString("object1").addString("object2")),
                  continueAfterNoErrorReply {
                    msgAfterGet =>
                      val data = msgAfterGet.body.getObject("data")
                      tu.azzert(someData.getObject("object2").equals(data.getObject("object2")), "object2 should still exist")
                      val object1 = someData.getObject("object1")
                      val newObject1 = data.getObject("object1")
                      tu.azzert(!object1.equals(newObject1), "object1 should be different now")
                      tu.azzert(newObject1.getString("key1") == null, "key1 should not exist anymore in object1")
                      tu.azzert(newObject1.getNumber("key2") == null, "key2 should not exist anymore in object1")
                      tu.azzert(newObject1.getBoolean("overwritten", false), "overwritten should be true in object1")

                      tu.testComplete
                  })
            })
      }
    }
  }

  def testCheckMatchInSessions() {
    import scala.collection.JavaConversions._
    afterClearDo {
      afterPutDo(new JsonObject().putString("teststring", "ok").putNumber("answer", 15)) {
        sessionId1 =>
          afterPutDo(new JsonObject().putString("teststring", "ok").putNumber("answer", 16)) {
            sessionId2 =>

              val asynchTestCount = 5
              val asynchTests = new AtomicInteger(0)

              eb.send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "notok")),
                continueAfterNoErrorReply {
                  reportMessage =>
                    tu.azzert(!reportMessage.body.getBoolean("matches", true), "should not match, but got "
                      + reportMessage.body.getArray("sessions").encode)
                    tu.azzert(reportMessage.body.getArray("sessions").size == 0, "0 sessions should match")
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      tu.testComplete
                    }
                })

              eb.send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "ok")),
                continueAfterNoErrorReply {
                  reportMessage =>
                    tu.azzert(reportMessage.body.getBoolean("matches", false), "should match!")
                    tu.azzert(reportMessage.body.getArray("sessions").size == 2, "2 sessions should match")
                    for (obj <- reportMessage.body.getArray("sessions")) {
                      val sessId = obj.asInstanceOf[JsonObject].getString("sessionId")
                      tu.azzert(sessionId1 == sessId || sessionId2 == sessId, "Session " + sessionId1 + " or " + sessionId2 + " should match with " + reportMessage.body.getString("sessionId"))
                    }
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      tu.testComplete
                    }
                })

              eb.send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putNumber("answer", 16)),
                continueAfterNoErrorReply {
                  reportMessage =>
                    tu.azzert(reportMessage.body.getBoolean("matches", false), "should match!")
                    tu.azzert(reportMessage.body.getArray("sessions").size == 1, "only 1 session should match")
                    for (obj <- reportMessage.body.getArray("sessions")) {
                      tu.azzert(sessionId2 == obj.asInstanceOf[JsonObject].getString("sessionId"), "Sessions " + sessionId2 + " should match with " + reportMessage.body.getString("sessionId"))
                    }
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      tu.testComplete
                    }
                })

              eb.send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "ok").putNumber("answer", 15)),
                continueAfterNoErrorReply {
                  reportMessage =>
                    tu.azzert(reportMessage.body.getBoolean("matches", false), "should match!")
                    tu.azzert(reportMessage.body.getArray("sessions").size == 1, "only 1 session should match")
                    for (obj <- reportMessage.body.getArray("sessions")) {
                      tu.azzert(sessionId1 == obj.asInstanceOf[JsonObject].getString("sessionId"), "Sessions " + sessionId1 + " should match with " + reportMessage.body.getString("sessionId"))
                    }
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      tu.testComplete
                    }
                })

              eb.send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "ok").putNumber("answer", 17)),
                continueAfterNoErrorReply {
                  reportMessage =>
                    tu.azzert(!reportMessage.body.getBoolean("matches", true), "should not match!")
                    tu.azzert(reportMessage.body.getArray("sessions").size == 0, "0 sessions should match")
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      tu.testComplete
                    }
                })
          }
      }
    }
  }

  def testConnectionsReport() {
    afterClearDo {
      val testCount = 2
      val testsRun = new AtomicInteger(0)

      // No open sessions after clear?
      afterClearDo {
        eb.send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"), continueAfterNoErrorReply {
          msgAfterStatus =>
            tu.azzert(msgAfterStatus.body.getNumber("openSessions", 1000) == 0, "There shouldn't be any open sessions anymore!")
            if (testsRun.incrementAndGet == testCount) {
              tu.testComplete
            }
        })
      }

      eb.send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"),
        continueAfterNoErrorReply {
          initialOpenSessionsMsg =>
            val initialOpenSessions = initialOpenSessionsMsg.body.getNumber("openSessions").intValue
            val asynchTestCount = 10
            val asynchTests = new AtomicInteger(0)

            for (i <- 1 to asynchTestCount) {
              afterCreateDo {
                otherSessionId =>
                  eb.send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"),
                    continueAfterNoErrorReply {
                      openSessionsMsg =>
                        val numberOfTest = asynchTests.incrementAndGet
                        if (numberOfTest == asynchTestCount) {
                          val openSessions = openSessionsMsg.body.getNumber("openSessions").intValue
                          tu.azzert(openSessions >= asynchTestCount, "There should be at least " + asynchTestCount + " open sessions.")
                          if (testsRun.incrementAndGet == testCount) {
                            tu.testComplete
                          }
                        }
                    })
              }
            }
        })
    }
  }

  def testDestroySession() {
    afterClearDo {
      afterPutAndGetDo {
        sessionId =>
          eb.send(smAddress,
            new JsonObject().putString("action", "destroy").putString("sessionId", sessionId),
            continueAfterNoErrorReply {
              msgAfterDestroy =>
                tu.azzert(msgAfterDestroy.body.getBoolean("sessionDestroyed", false), "session should have been destroyed")

                val asynchTestCount = 2
                val asynchTests = new AtomicInteger(0)

                eb.send(smAddress,
                  new JsonObject().putString("action", "get").putString("sessionId", sessionId)
                    .putArray("fields", new JsonArray().addString("object1")),
                  continueAfterErrorReply("SESSION_GONE") {
                    msgAfterGet2 =>
                      if (asynchTests.incrementAndGet == asynchTestCount) {
                        tu.testComplete()
                      }
                  })

                afterGetOpenSessionsDo {
                  openSessions =>
                    tu.azzert(openSessions == 0, "There shouldn't be any open sessions anymore, but got: " + openSessions)

                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      tu.testComplete()
                    }
                }
            })
      }
    }
  }

  def testCleanupAfterSession() {
    afterClearDo {
      var unregisterIt = { () => }
      val handler = new Handler[Message[JsonObject]]() {
        def handle(msg: Message[JsonObject]) {
          tu.azzert(msg.body.getObject("session") != null, "Should get a real session for cleanup!")
          msg.body.getObject("session") match {
            case session =>
              container.getLogger.info("Got session: " + session.encode)
              tu.azzert(msg.body.getString("sessionId") != null, "should have a sessionId")
              tu.azzert(someData.equals(session), "session should equal someData")
              eb.unregisterHandler(cleanupAddress, this)
              tu.testComplete
          }
        }
      }

      eb.registerHandler(cleanupAddress, handler)

      afterPutDo {
        sessionId =>
          eb.send(smAddress, new JsonObject().putString("action", "destroy").putString("sessionId", sessionId))
      }
    }
  }

  def testNoSessionsAfterTimeout() {
    afterClearDo {
      afterCreateDo { sessionId =>
        Thread.sleep(defaultTimeout + 100)
        eb.send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"),
          continueAfterNoErrorReply {
            openSessionsMsg =>
              val openSessions = openSessionsMsg.body.getNumber("openSessions").intValue
              tu.azzert(openSessions == 0, "Waited long enough - there shouldn't be any open sessions anymore, but got: " + openSessions)
              tu.testComplete
          })
      }
    }
  }

  def testTimeoutSession() {
    afterClearDo {
      afterPutDo {
        sessionId =>
          var statusReceived = false

          val address = sessionClientPrefix + sessionId
          val handler = new Handler[Message[JsonObject]]() {
            override def handle(msg: Message[JsonObject]) {
              tu.azzert("error" == msg.body.getString("status"))
              val statusMessage = msg.body.getString("error")
              tu.azzert(statusMessage == "SESSION_TIMEOUT", "Error tag should have been SESSION_TIMEOUT, but got: " + statusMessage)
              statusReceived = true
              eb.unregisterHandler(address, this)
            }
          }
          eb.registerHandler(address, handler)

          Thread.sleep(timeoutTest)

          eb.send(smAddress, new JsonObject().putString("action", "get")
            .putString("sessionId", sessionId)
            .putArray("fields", new JsonArray().addString("teststring")),
            continueAfterErrorReply("SESSION_GONE") {
              errorMsg =>
                tu.azzert(statusReceived, "Did not receive status 'Session timeout.'!")
                tu.testComplete
            })
      }
    }
  }

  def testNoTimeoutSession() {
    afterClearDo {
      afterPutDo {
        sessionId =>
          Thread.sleep(noTimeoutTest)
          getSessionData(sessionId, new JsonArray().addString("teststring")) {
            data =>
              tu.azzert("ok".equals(data.getString("teststring")))
              Thread.sleep(noTimeoutTest)
              getSessionData(sessionId, new JsonArray().addString("teststring")) {
                data2 =>
                  tu.azzert("ok".equals(data2.getString("teststring")))
                  tu.testComplete
              }
          }
      }
    }
  }

  def testHeartbeatSession() {
    afterClearDo {
      afterPutDo {
        sessionId =>
          Thread.sleep(noTimeoutTest)
          eb.send(smAddress, new JsonObject().putString("action", "heartbeat").putString("sessionId", sessionId),
            continueAfterNoErrorReply { msg =>
              Thread.sleep(noTimeoutTest)
              eb.send(smAddress, new JsonObject().putString("action", "heartbeat").putString("sessionId", sessionId),
                continueAfterNoErrorReply { msg =>
                  Thread.sleep(noTimeoutTest)
                  getSessionData(sessionId, new JsonArray().addString("teststring")) {
                    data =>
                      tu.azzert("ok".equals(data.getString("teststring")))
                      tu.testComplete
                  }
                })
            })
      }
    }
  }

  // TODO test implementieren
  def testUnlimitedTimeout() {
    val config = new JsonObject().putNumber("timeout", 0).putString("address", "session2")
    val moduleId = container.deployModule("com.campudus.session-manager-v1.0", config, 1, new Handler[java.lang.String] {
      def handle(res: String) {

      }
    })

  }
}
