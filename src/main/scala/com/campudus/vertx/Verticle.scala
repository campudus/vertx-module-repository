package com.campudus.vertx

class Verticle extends org.vertx.java.platform.Verticle {

  lazy val logger = container.logger()

  implicit val ec = new scala.concurrent.ExecutionContext() {
    def execute(runnable: Runnable): Unit = runnable.run()
    def reportFailure(t: Throwable): Unit = logger.error("problem: " + t)
  }

}