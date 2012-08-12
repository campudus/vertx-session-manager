package com.campudus.vertx.sessionmanager;

import org.junit.Test
import org.vertx.java.framework.TestBase

class SessionManagerTest extends TestBase {

  @throws(classOf[Exception])
  override protected def setUp() {
    super.setUp()
    startApp(classOf[SessionManagerTestClient].getName())
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
  def testWrongDataTypes() = startTest(getMethodName())

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
  def testHeartbeatSession() = startTest(getMethodName())

}

