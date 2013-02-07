package com.campudus.vertx.sessionmanager

class SessionException(val errorId: String, message: String = null, cause: Throwable = null) extends Exception(message, cause)
