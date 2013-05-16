package com.campudus.vertx.helpers

import java.net.URI
import scala.concurrent.{ Future, Promise }
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.buffer.Buffer
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.file.AsyncFile
import org.vertx.java.core.http.HttpClientResponse
import org.vertx.java.core.json.JsonObject
import com.campudus.vertx.Verticle
import com.campudus.vertxmoduleregistry.ModuleRegistryStarter
import com.campudus.vertxmoduleregistry.ModuleRegistryException
import com.campudus.vertxmoduleregistry.ModuleRegistryException
import com.campudus.vertxmoduleregistry.ModuleRegistryException
import com.campudus.vertxmoduleregistry.ModuleRegistryException
import com.campudus.vertxmoduleregistry.ModuleRegistryException

trait VertxFutureHelpers extends VertxScalaHelpers {
  this: Verticle =>
  import com.campudus.vertx.DefaultVertxExecutionContext._

  private def futurify[T](doSomething: Promise[T] => Unit) = {
    val promise = Promise[T]

    doSomething(promise)

    promise.future
  }

  def open(name: String): Future[AsyncFile] = futurify[AsyncFile] { promise =>
    println("opening file " + name)
    getVertx().fileSystem().open(name, { ar: AsyncResult[AsyncFile] =>
      println("result: " + ar.result())
      if (ar.succeeded) {
        promise.success(ar.result)
      } else {
        promise.failure(new ModuleRegistryException("Opening of file / directory '" + name + "' failed: " + ar.cause, ar.cause))
      }
    })
  }

  def downloadInto(url: URI, into: AsyncFile): Future[AsyncFile] = futurify[AsyncFile] { promise =>
    val host = url.getHost
    val port = if (url.getPort() != -1) {
      url.getPort()
    } else {
      80
    }
    val uri = url.getPath()

    val client = getVertx().createHttpClient.setHost(host).setPort(port).setKeepAlive(true)

    println("downloading " + uri + " from " + host + ":" + port)
    val request = client.get(uri, { resp: HttpClientResponse =>
      if (resp.statusCode() != 200) {
        println("Not the right status code: " + resp.statusCode())
        println("headers:")
        println(resp.headers().entries())
        client.close()
        promise.failure(new ModuleRegistryException("could not open " + uri + " on " + host + ":" + port))
      } else {
        val buf = new Buffer(0)

        resp.dataHandler({ buffer: Buffer =>
          println("writing data into file...")
          into.write(buffer)
        })
        resp.endHandler({ () =>
          println("downloading done")
          client.close()
          into.close()
          promise.success(into)
        })
      }
    })

    client.exceptionHandler({ ex: Throwable =>
      println("got an exception in client: " + ex)
      promise.failure(new ModuleRegistryException("Download problem: " + ex, cause = ex))
    })

    request.putHeader("Host", host)
    request.putHeader("User-Agent", "Vert.x Module Registry")

    println("request-headers: " + request.headers().entries())
    request.end()
  }

  def extract(filename: String): Future[String] = futurify[String] { promise =>
    println("extracting file " + filename)
    getVertx().eventBus().send(ModuleRegistryStarter.unzipAddress,
      json.putString("zipFile", filename) /*.putBoolean("deleteZip", true)*/ ,
      { msg: Message[JsonObject] =>
        msg.body.getString("status") match {
          case "ok" =>
            promise.success(msg.body.getString("destDir"))
          case _ =>
            promise.failure(new ModuleRegistryException(msg.body.getString("message")))
        }
      })
  }

  def modFileNameFromExtractedModule(dir: String): Future[String] = futurify[String] { promise =>
    println("reading directory " + dir)
    getVertx.fileSystem().readDir(dir, { ar: AsyncResult[Array[String]] =>
      if (ar.succeeded()) {
        val listOfEntries = ar.result.toList
        println("read stuff from dir: " + listOfEntries)
        listOfEntries.filter(_.endsWith("mod.json")).headOption match {
          case None => promise.failure(new ModuleRegistryException("Could not find mod.json in downloaded / extracted file"))
          case Some(modJson) => promise.success(modJson)
        }
      } else {
        promise.failure(new ModuleRegistryException("Reading extracted module directory failed: " + ar.cause, ar.cause))
      }
    })
  }

  def writeToFile(file: AsyncFile, buf: Buffer): Future[Unit] = futurify[Unit] { promise =>
    println("writing into " + file)
    file.write(buf, 0, { ar: AsyncResult[Void] =>
      if (ar.succeeded()) {
        promise.success(ar.result())
      } else {
        promise.failure(new ModuleRegistryException("Writing to file failed: " + ar.cause, ar.cause))
      }
    })
  }

  def readFileToString(file: AsyncFile): Future[Buffer] = futurify[Buffer] { promise =>
    println("reading file " + file)
    file.exceptionHandler({ ex: Throwable =>
      println("reading fail: " + ex.getMessage())
      ex.printStackTrace()
      promise.failure(new ModuleRegistryException("Reading of file failed: " + ex, ex))
    })

    val buffer = new Buffer(0)
    file.dataHandler({ buf: Buffer =>
      println("data into buffer (read)...")
      buffer.appendBuffer(buf)
    })
    file.endHandler({ () =>
      println("done reading " + file)
      promise.success(buffer)
    })
  }

}
