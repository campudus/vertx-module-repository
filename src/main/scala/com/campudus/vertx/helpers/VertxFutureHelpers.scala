package com.campudus.vertx.helpers

import java.net.URL

import scala.concurrent.Future
import scala.concurrent.Promise

import org.vertx.java.core.AsyncResult
import org.vertx.java.core.buffer.Buffer
import org.vertx.java.core.file.AsyncFile
import org.vertx.java.core.http.HttpClientResponse
import org.vertx.java.core.streams.Pump
import org.vertx.java.platform.Verticle

trait VertxFutureHelpers extends VertxScalaHelpers {
  this: Verticle =>

  def open(name: String): Future[AsyncFile] = {
    val promise = Promise[AsyncFile]

    vertx.fileSystem().open(name, { ar: AsyncResult[AsyncFile] =>
      if (ar.succeeded) {
        promise.success(ar.result)
      } else {
        promise.failure(ar.cause)
      }
    })

    promise.future
  }

  def download(url: URL, into: AsyncFile): Future[AsyncFile] = {
    val promise = Promise[AsyncFile]

    vertx.createHttpClient.setHost(url.getHost).get(url.getPath, {
      resp: HttpClientResponse =>
        resp.exceptionHandler({ ex: Throwable => promise.failure(ex) })
        resp.endHandler({ () => promise.success(into) })
        val pump = Pump.createPump(resp, into)
        pump.start()
    })

    promise.future
  }

  def createDir(dir: String): Future[Boolean] = {
    val promise = Promise[Boolean]

    vertx.fileSystem().mkdir(dir, true, { ar: AsyncResult[Void] =>
      promise.success(ar.succeeded)
    })

    promise.future
  }

  def extract(zip: AsyncFile, dir: String): Future[Boolean] = {
    val promise = Promise[Boolean]

    // FIXME implement extract GZIP
    promise.failure(???)

    promise.future
  }

  def fileToString(file: AsyncFile): Future[String] = {
    val promise = Promise[String]

    file.exceptionHandler({ ex: Throwable => promise.failure(ex) })

    val buffer = new Buffer(0)
    file.dataHandler({ buf: Buffer => buffer.appendBuffer(buf) })
    file.endHandler({ () => promise.success(buffer.toString) })

    promise.future
  }

}
