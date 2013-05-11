package com.campudus.vertx.sessionmanager;

import org.junit.Test
import org.vertx.java.core.json.JsonObject
import org.vertx.testtools.TestVerticle
import org.vertx.testtools.VertxAssert._
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.AsyncResult
import scala.concurrent.Future

class SessionManagerSharedDataTest extends SessionManagerBaseTestClient {

  protected def setUp() = Future.successful()

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

