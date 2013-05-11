package com.campudus.vertx.sessionmanager

import org.vertx.testtools.TestVerticle
import org.vertx.testtools.VertxAssert._
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.AsyncResult
import scala.concurrent.Future
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonArray
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.vertx.java.core.Handler

abstract class SessionManagerBaseTestClient extends TestVerticle with VertxScalaHelpers {
  import com.campudus.vertx.DefaultVertxExecutionContext.global

  val smAddress = "sessions"
  val cleanupAddress = "sessioncleaner"
  val defaultTimeout = 5000
  val noTimeoutTest = 3500
  val timeoutTest = 10000
  val sessionClientPrefix = "session."

  val config = new JsonObject()
  config.putNumber("defaultTimeout", defaultTimeout)
  config.putNumber("noTimeoutTest", noTimeoutTest)
  config.putNumber("timeoutTest", timeoutTest)

  val sessionManagerConfig = new JsonObject()
  sessionManagerConfig.putString("address", smAddress)
  sessionManagerConfig.putString("cleaner", cleanupAddress)
  sessionManagerConfig.putNumber("timeout", defaultTimeout)
  sessionManagerConfig.putString("prefix", sessionClientPrefix)
  config.putObject("sessionManagerConfig", sessionManagerConfig)

  private val someData = new JsonObject()
    .putObject("object1", new JsonObject().putString("key1", "something").putNumber("key2", 15))
    .putObject("object2", new JsonObject().putString("key1", "another thing").putNumber("key2", 16))
    .putString("teststring", "ok")
    .putNumber("answer", 42)

  private def deployAndStart() = {
    // Deploy the module - the System property `vertx.modulename` will contain the name of the module so you
    // don't have to hardcode it in your tests
    container.deployModule(System.getProperty("vertx.modulename"), sessionManagerConfig, { asyncResult: AsyncResult[String] =>
      // Deployment is asynchronous and this this handler will be called when it's complete (or failed)
      assertTrue("should succeed with deployment", asyncResult.succeeded())
      assertNotNull("deploymentID should not be null", asyncResult.result())
      // If deployed correctly then start the tests!
      startTests()
    })
  }

  override def start() {
    // Make sure we call initialize() - this sets up the assert stuff so assert functionality works correctly
    initialize()

    setUp map (_ => deployAndStart())
  }

  /**
   * Does some basic initialization.
   *
   * @return A future when the function is done.
   */
  protected def setUp(): Future[Unit]

  private def continueAfterNoErrorReply(fn: (Message[JsonObject]) => Unit) = { msg: Message[JsonObject] =>
    msg.body.getString("status") match {
      case "ok" => fn(msg)
      case "error" => fail("Should not get an error, but got: " + msg.body.getString("error") + " - " + msg.body.getString("message"))
      case str => fail("Should get an ok status, but got different status: " + str)
    }
  }

  private def continueAfterErrorReply(error: String)(fn: (Message[JsonObject]) => Unit) = { msg: Message[JsonObject] =>
    msg.body.getString("status") match {
      case "ok" => fail("Should get an error, but got ok! " + msg.body.encode)
      case "error" =>
        if (error != null) {
          assertEquals(error + " does not equal " + msg.body.getString("error"), error, msg.body.getString("error"))
        }
        fn(msg)
      case str => fail("Should get an error status, but got different status: " + str)
    }
  }

  private def afterClearDo(after: => Unit) {
    getVertx().eventBus().send(smAddress, json.putString("action", "clear"),
      continueAfterNoErrorReply {
        msg =>
          assertTrue(msg.body.getBoolean("cleared", false))
          after
      })
  }

  private def afterCreateDo(after: String => Unit) {
    val json = new JsonObject().putString("action", "start")

    getVertx().eventBus.send(smAddress, json, continueAfterNoErrorReply {
      msg =>
        assertNotNull("sessionId should be a string after create, but is null.", msg.body.getString("sessionId"))
        after(msg.body.getString("sessionId"))
    })
  }

  private def getSessionData(sessionId: String, fields: JsonArray)(after: JsonObject => Unit) {
    getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "get")
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
        getVertx().eventBus().send(smAddress,
          new JsonObject().putString("action", "put")
            .putString("sessionId", sessionId)
            .putObject("data", data),
          continueAfterNoErrorReply {
            msgAfterPut =>
              assertTrue("Session should have been saved!", msgAfterPut.body.getBoolean("sessionSaved", false))
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
          getVertx().eventBus().send(smAddress,
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
                assertNotNull("Should receive data, but got null", data != null)
                val object1 = data.getObject("object1")
                assertEquals("Should have gotten something out of data, but got " + object1.getString("key1"), "something", object1.getString("key1"))
                assertEquals("Should have gotten 15 out of data, but got " + object1.getNumber("key2"), 15, object1.getNumber("key2").intValue)
                val object2 = data.getObject("object2")
                assertEquals("Should have gotten another thing out of data, but got " + object2.getString("key1"), "another thing", object2.getString("key1"))
                assertEquals("Should have gotten 16 out of data, but got " + object2.getNumber("key2"), 16, object2.getNumber("key2").intValue)
                val object3 = data.getObject("object3")
                assertNull("where did object3 come from?", object3)
                val teststring = data.getString("teststring")
                assertEquals("Should get a teststring", "ok", teststring)
                val answer = data.getNumber("answer")
                assertEquals("Should get 42 as answer", 42, answer.intValue)

                if (asynchTests.incrementAndGet == asynchTestCount) {
                  after(sessionId)
                }
            })

          // Get saved stuff partially
          getVertx().eventBus().send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId)
              .putArray("fields", new JsonArray().addString("object2").addString("teststring")),
            continueAfterNoErrorReply {
              msgAfterGet =>
                val data = msgAfterGet.body.getObject("data")
                assertNotNull("Should receive data, but got null", data)
                val object1 = data.getObject("object1")
                assertNull("object1 should not be in the result!", object1)
                val object2 = data.getObject("object2")
                assertEquals("Should have gotten another thing out of data, but got " + object2.getString("key1"), "another thing", object2.getString("key1"))
                assertEquals("Should have gotten 16 out of data, but got " + object2.getNumber("key2"), 16, object2.getNumber("key2").intValue)
                val teststring = data.getString("teststring")
                assertEquals("Should get a teststring", "ok", teststring)
                val answer = data.getNumber("answer")
                assertNull("Should not get the answer", answer)

                if (asynchTests.incrementAndGet == asynchTestCount) {
                  after(sessionId)
                }
            })
      }
    }
  }

  private def afterGetOpenSessionsDo(after: Int => Unit) {
    getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"), continueAfterNoErrorReply {
      msgAfterStatus =>
        after(msgAfterStatus.body.getNumber("openSessions").intValue)
    })
  }

  def testClear() {
    afterClearDo {
      testComplete()
    }
  }

  def testCreateSession() {
    afterClearDo {
      afterCreateDo {
        sessionId =>
          testComplete()
      }
    }
  }

  def testPutSession() {
    afterClearDo {
      afterPutDo {
        sessionId =>
          // Provoke an error message because of an invalid sessionId
          getVertx().eventBus().send(smAddress,
            new JsonObject().putString("action", "put")
              .putString("sessionId", sessionId + "123")
              .putObject("data", someData),
            continueAfterErrorReply("SESSION_GONE") {
              msgAfterErrorPut =>
                testComplete()
            })
      }
    }
  }

  def testPutAndGetSession() {
    afterClearDo {
      afterPutAndGetDo {
        sessionId =>
          testComplete()
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
          getVertx().eventBus().send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId + "123")
              .putArray("fields", new JsonArray().addString("teststring")),
            continueAfterErrorReply("SESSION_GONE") {
              msgAfterErrorPut =>
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  testComplete()
                }
            })

          // Results in an error, field missing
          getVertx().eventBus().send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId),
            continueAfterErrorReply("FIELDS_MISSING") {
              msgAfterErrorPut =>
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  testComplete()
                }
            })

          // Results in an error, field missing
          getVertx().eventBus().send(smAddress,
            new JsonObject().putString("action", "get")
              .putString("sessionId", sessionId)
              .putString("fields", "teststring"),
            continueAfterNoErrorReply {
              msgAfterNoErrorPut =>
                assertEquals("Should result in the teststring 'ok'", "ok", msgAfterNoErrorPut.body.getObject("data").getString("teststring"))
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  testComplete()
                }
            })
      }
    }
  }

  def testCheckErrorTypes() {
    afterPutDo {
      sessionId =>
        val asynchTestCount = 11
        val asynchTests = new AtomicInteger(0)

        def errorTest(json: JsonObject, error: String) = {
          getVertx().eventBus().send(smAddress, json,
            continueAfterErrorReply(error) {
              errorMessage =>
                if (asynchTests.incrementAndGet == asynchTestCount) {
                  testComplete()
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

        // fields parameter missing in get
        errorTest(new JsonObject().putString("action", "get")
          .putString("sessionId", sessionId),
          "FIELDS_MISSING")

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
          getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "put")
            .putString("sessionId", sessionId)
            .putObject("data", new JsonObject()
              .putObject("object1", new JsonObject().putBoolean("overwritten", true))),
            continueAfterNoErrorReply {
              msgAfterOverride =>
                getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "get")
                  .putString("sessionId", sessionId)
                  .putArray("fields", new JsonArray().addString("object1").addString("object2")),
                  continueAfterNoErrorReply {
                    msgAfterGet =>
                      val data = msgAfterGet.body.getObject("data")
                      assertEquals("object2 should still exist", someData.getObject("object2"), (data.getObject("object2")))
                      val object1 = someData.getObject("object1")
                      val newObject1 = data.getObject("object1")
                      assertFalse("object1 should be different now", object1.equals(newObject1))
                      assertNull("key1 should not exist anymore in object1", newObject1.getString("key1"))
                      assertNull("key2 should not exist anymore in object1", newObject1.getNumber("key2"))
                      assertTrue("overwritten should be true in object1", newObject1.getBoolean("overwritten", false))

                      testComplete()
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

              getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "notok")),
                continueAfterNoErrorReply {
                  reportMessage =>
                    assertFalse("should not match, but got " + reportMessage.body.getArray("sessions").encode, reportMessage.body.getBoolean("matches", true))
                    assertEquals("0 sessions should match", 0, reportMessage.body.getArray("sessions").size)
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      testComplete()
                    }
                })

              getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "ok")),
                continueAfterNoErrorReply {
                  reportMessage =>
                    assertTrue("should match!", reportMessage.body.getBoolean("matches", false))
                    assertEquals("2 sessions should match", 2, reportMessage.body.getArray("sessions").size)
                    for (obj <- reportMessage.body.getArray("sessions")) {
                      val sessId = obj.asInstanceOf[JsonObject].getString("sessionId")
                      assertTrue("Session " + sessionId1 + " or " + sessionId2 + " should match with " + reportMessage.body.getString("sessionId"), sessionId1 == sessId || sessionId2 == sessId)
                    }
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      testComplete()
                    }
                })

              getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putNumber("answer", 16)),
                continueAfterNoErrorReply {
                  reportMessage =>
                    assertTrue("should match!", reportMessage.body.getBoolean("matches", false))
                    assertEquals("only 1 session should match", 1, reportMessage.body.getArray("sessions").size)
                    for (obj <- reportMessage.body.getArray("sessions")) {
                      assertEquals("Sessions " + sessionId2 + " should match with " + reportMessage.body.getString("sessionId"), sessionId2, obj.asInstanceOf[JsonObject].getString("sessionId"))
                    }
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      testComplete()
                    }
                })

              getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "ok").putNumber("answer", 15)),
                continueAfterNoErrorReply {
                  reportMessage =>
                    assertTrue("should match!", reportMessage.body.getBoolean("matches", false))
                    assertEquals("only 1 session should match", 1, reportMessage.body.getArray("sessions").size)
                    for (obj <- reportMessage.body.getArray("sessions")) {
                      assertEquals("Sessions " + sessionId1 + " should match with " + reportMessage.body.getString("sessionId"), sessionId1, obj.asInstanceOf[JsonObject].getString("sessionId"))
                    }
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      testComplete()
                    }
                })

              getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status")
                .putString("report", "matches")
                .putObject("data", new JsonObject().putString("teststring", "ok").putNumber("answer", 17)),
                continueAfterNoErrorReply {
                  reportMessage =>
                    assertFalse("should not match!", reportMessage.body.getBoolean("matches", true))
                    assertEquals("0 sessions should match", 0, reportMessage.body.getArray("sessions").size)
                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      testComplete()
                    }
                })
          }
      }
    }
  }

  def testConnectionsReport() {
    afterClearDo {

      getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"),
        continueAfterNoErrorReply {
          initialOpenSessionsMsg =>
            val initialOpenSessions = initialOpenSessionsMsg.body.getNumber("openSessions").intValue
            val asynchTestCount = 10
            val asynchTests = new AtomicInteger(0)

            for (i <- 1 to asynchTestCount) {
              afterCreateDo {
                otherSessionId =>
                  getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"),
                    continueAfterNoErrorReply {
                      openSessionsMsg =>
                        val numberOfTest = asynchTests.incrementAndGet
                        if (numberOfTest == asynchTestCount) {
                          val openSessions = openSessionsMsg.body.getNumber("openSessions").intValue
                          assertTrue("There should be at least " + asynchTestCount + " open sessions.", openSessions >= asynchTestCount)

                          // No open sessions after clear?
                          afterClearDo {
                            getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"), continueAfterNoErrorReply {
                              msgAfterStatus =>
                                val openSessions = msgAfterStatus.body.getNumber("openSessions", 1000)
                                assertEquals("There shouldn't be any open sessions anymore, but got " + openSessions, 0, openSessions)
                                testComplete()
                            })
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
          getVertx().eventBus().send(smAddress,
            new JsonObject().putString("action", "destroy").putString("sessionId", sessionId),
            continueAfterNoErrorReply {
              msgAfterDestroy =>
                assertTrue("session should have been destroyed", msgAfterDestroy.body.getBoolean("sessionDestroyed", false))

                val asynchTestCount = 2
                val asynchTests = new AtomicInteger(0)

                getVertx().eventBus().send(smAddress,
                  new JsonObject().putString("action", "get").putString("sessionId", sessionId)
                    .putArray("fields", new JsonArray().addString("object1")),
                  continueAfterErrorReply("SESSION_GONE") {
                    msgAfterGet2 =>
                      if (asynchTests.incrementAndGet == asynchTestCount) {
                        testComplete()
                      }
                  })

                afterGetOpenSessionsDo {
                  openSessions =>
                    assertEquals("There shouldn't be any open sessions anymore, but got: " + openSessions, 0, openSessions)

                    if (asynchTests.incrementAndGet == asynchTestCount) {
                      testComplete()
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
        override def handle(msg: Message[JsonObject]) {
          assertNotNull("Should get a real session for cleanup, but got " + msg.body.encode, msg.body.getObject("session"))
          msg.body.getObject("session") match {
            case session =>
              assertNotNull("should have a sessionId", msg.body.getString("sessionId"))
              assertEquals("session should equal someData", someData, session)
              getVertx().eventBus().unregisterHandler(cleanupAddress, this)
              testComplete()
          }
        }
      }

      getVertx().eventBus().registerHandler(cleanupAddress, handler)

      afterPutDo {
        sessionId =>
          getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "destroy").putString("sessionId", sessionId))
      }
    }
  }

  def testNoSessionsAfterTimeout() {
    afterClearDo {
      afterCreateDo { sessionId =>
        Thread.sleep(defaultTimeout + 100)
        getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "status").putString("report", "connections"),
          continueAfterNoErrorReply {
            openSessionsMsg =>
              val openSessions = openSessionsMsg.body.getNumber("openSessions").intValue
              assertEquals("Waited long enough - there shouldn't be any open sessions anymore, but got: " + openSessions, 0, openSessions)
              testComplete()
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
              assertEquals("error", msg.body.getString("status"))
              val statusMessage = msg.body.getString("error")
              assertEquals("Error tag should have been SESSION_TIMEOUT, but got: " + statusMessage, "SESSION_TIMEOUT", statusMessage)
              statusReceived = true
              getVertx().eventBus().unregisterHandler(address, this)
            }
          }
          getVertx().eventBus().registerHandler(address, handler)

          Thread.sleep(timeoutTest)

          getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "get")
            .putString("sessionId", sessionId)
            .putArray("fields", new JsonArray().addString("teststring")),
            continueAfterErrorReply("SESSION_GONE") {
              errorMsg =>
                assertTrue("Did not receive status 'Session timeout.'!", statusReceived)
                testComplete()
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
              assertEquals("ok", data.getString("teststring"))
              Thread.sleep(noTimeoutTest)
              getSessionData(sessionId, new JsonArray().addString("teststring")) {
                data2 =>
                  assertEquals("ok", data2.getString("teststring"))
                  testComplete()
              }
          }
      }
    }
  }

  def testHeartbeatSession() {
    afterClearDo {
      afterPutAndGetDo {
        sessionId =>
          Thread.sleep(noTimeoutTest)
          println("first")
          getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "heartbeat").putString("sessionId", sessionId),
            continueAfterNoErrorReply { msg =>
              println("second")
              Thread.sleep(noTimeoutTest)
              getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "heartbeat").putString("sessionId", sessionId),
                continueAfterNoErrorReply { msg =>
                  println("third")
                  Thread.sleep(noTimeoutTest)
                  getSessionData(sessionId, new JsonArray().addString("teststring")) {
                    data =>
                      println("session data")
                      assertEquals("ok", data.getString("teststring"))
                      testComplete()
                  }
                })
            })
      }
    }
  }

  def testErrorOnHeartbeatWithNotExistingSession() {
    afterClearDo {
      getVertx().eventBus().send(smAddress, new JsonObject().putString("action", "heartbeat").putString("sessionId", "not-existing"), continueAfterErrorReply("UNKNOWN_SESSIONID") {
        msg =>
          testComplete()
      })
    }
  }
}