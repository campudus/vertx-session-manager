package com.campudus.vertx

trait VertxExecutionContext {
  implicit val global = new scala.concurrent.ExecutionContext() {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(t: Throwable): Unit = println("failed while executing in vertx context", t)
  }
}

object DefaultVertxExecutionContext extends VertxExecutionContext
