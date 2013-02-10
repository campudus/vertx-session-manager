package com.campudus.vertx.sessionmanager

class SessionException(val errorId: String, message: String = null, cause: Throwable = null) extends Exception(message, cause)

object SessionException {
  def gone() = new SessionException("SESSION_GONE", "The session id does not exist")
}