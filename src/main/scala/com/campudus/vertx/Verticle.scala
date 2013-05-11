package com.campudus.vertx

trait Verticle extends org.vertx.java.platform.Verticle with VertxExecutionContext {

  lazy val logger = getContainer().logger()

}