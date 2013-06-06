package com.campudus.vertx.sessionmanager;

import scala.concurrent.Promise

import org.junit.Test
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.json.JsonObject

class SessionManagerMongoDbTest extends SessionManagerBaseTestClient {

  sessionManagerConfig.putObject("mongo-sessions", new JsonObject().putString("address", "mongo").putString("collection", "sessions"))
  config.putObject("sessionManagerConfig", sessionManagerConfig)

  val mongoConfig = new JsonObject()
  mongoConfig.putString("address", "mongo")
  mongoConfig.putString("db_name", "vertx-test")
  config.putObject("mongoConfig", mongoConfig)

  override protected def setUp() = {
    val promise = Promise[Unit]
    container.deployModule("io.vertx~mod-mongo-persistor~2.0.0-SNAPSHOT", mongoConfig, { ar: AsyncResult[String] =>
      if (ar.succeeded()) {
        promise.success()
      } else {
        promise.failure(new RuntimeException("could not deploy mongo-persistor: " + ar.cause().getMessage() + "\n" + ar.cause().getStackTraceString))
      }
    })
    promise.future
  }

  @Test
  override def testClear() = super.testClear

  @Test
  override def testCreateSession() = super.testCreateSession

  @Test
  override def testPutSession() = super.testPutSession

  @Test
  override def testPutAndGetSession() = super.testPutAndGetSession

  @Test
  override def testGetFieldsSession() = super.testGetFieldsSession

  @Test
  override def testCheckErrorTypes() = super.testCheckErrorTypes

  @Test
  override def testOverwriteSessionData() = super.testOverwriteSessionData

  @Test
  override def testCheckMatchInSessions() = super.testCheckMatchInSessions

  @Test
  override def testConnectionsReport() = super.testConnectionsReport

  @Test
  override def testDestroySession() = super.testDestroySession

  @Test
  override def testCleanupAfterSession() = super.testCleanupAfterSession

  @Test
  override def testNoSessionsAfterTimeout() = super.testNoSessionsAfterTimeout

  @Test
  override def testTimeoutSession() = super.testTimeoutSession

  @Test
  override def testNoTimeoutSession() = super.testNoTimeoutSession

  @Test
  override def testHeartbeatSession() = super.testHeartbeatSession

  @Test
  override def testErrorOnHeartbeatWithNotExistingSession() = super.testErrorOnHeartbeatWithNotExistingSession

}
