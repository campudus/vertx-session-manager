package com.campudus.vertx.sessionmanager

import org.vertx.java.core.AsyncResult
import org.vertx.java.core.Handler
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.AsyncResultHandler

trait VertxScalaTestHelpers {

  def json = new JsonObject

  implicit def fnToHandler[T](fn: T => Any): Handler[T] = new Handler[T]() {
    override def handle(event: T) = fn(event)
  }

  implicit def noParameterFunctionToSimpleHandler(fn: () => Any): Handler[Void] = new Handler[Void]() {
    override def handle(v: Void) = fn()
  }

  implicit def somethingToAsyncResult[T](something: T): AsyncResult[T] = new AsyncResult(something)

  implicit def fnToAsyncHandler[T](fn: AsyncResult[T] => Any): AsyncResultHandler[T] = new AsyncResultHandler[T]() {
    override def handle(result: AsyncResult[T]) = fn(result)
  }
}

object VertxScalaTestHelpers extends VertxScalaTestHelpers