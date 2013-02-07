package com.campudus.vertx.sessionmanager;

import org.junit.Test
import org.vertx.java.testframework.TestBase
import org.vertx.java.core.json.JsonObject

class SessionManagerSharedDataTest extends TestBase {

  val config = new JsonObject()
  config.putString("version", "1.2.0")
  config.putNumber("defaultTimeout", 5000)
  config.putNumber("noTimeoutTest", 3500)
  config.putNumber("timeoutTest", 10000)
  
  val sessionManagerConfig= new JsonObject()
  sessionManagerConfig.putString("address", "sessions")
  sessionManagerConfig.putString("cleaner", "sessioncleaner")
  sessionManagerConfig.putNumber("timeout", 5000)
  sessionManagerConfig.putString("prefix", "session.")
  config.putObject("sessionManagerConfig", sessionManagerConfig)

  @throws(classOf[Exception])
  override protected def setUp() {
    super.setUp()
    startApp(classOf[SessionManagerTestClient].getName(),config)
  }

  @throws(classOf[Exception])
  override protected def tearDown() {
    super.tearDown()
  }

  @Test
  def testClear() = startTest(getMethodName())

  @Test
  def testCreateSession() = startTest(getMethodName())

  @Test
  def testPutSession() = startTest(getMethodName())

  @Test
  def testPutAndGetSession() = startTest(getMethodName())

  @Test
  def testGetFieldsSession() = startTest(getMethodName())

  @Test
  def testCheckErrorTypes() = startTest(getMethodName())

  @Test
  def testOverwriteSessionData() = startTest(getMethodName())

  @Test
  def testCheckMatchInSessions() = startTest(getMethodName())

  @Test
  def testConnectionsReport() = startTest(getMethodName())

  @Test
  def testDestroySession() = startTest(getMethodName())

  @Test
  def testCleanupAfterSession() = startTest(getMethodName())

  @Test
  def testTimeoutSession() = startTest(getMethodName())

  @Test
  def testNoSessionsAfterTimeout() = startTest(getMethodName())

  @Test
  def testNoTimeoutSession() = startTest(getMethodName())

  @Test
  def testErrorOnHeartbeatWithNotExistingSession() = startTest(getMethodName())

  @Test
  def testHeartbeatSession() = startTest(getMethodName())

}

