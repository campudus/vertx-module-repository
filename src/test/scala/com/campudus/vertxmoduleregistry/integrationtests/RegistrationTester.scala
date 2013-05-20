package com.campudus.vertxmoduleregistry.integrationtests

import org.junit.Test
import org.vertx.java.core.Handler
import org.vertx.testtools.VertxAssert._
import org.vertx.java.core.AsyncResult
import org.junit.Before
import org.vertx.java.core.http.HttpClient
import org.vertx.java.core.http.HttpClientRequest
import org.vertx.java.core.http.HttpClientResponse
import org.vertx.java.core.buffer.Buffer
import scala.concurrent.Future
import org.vertx.java.core.json.JsonObject
import scala.concurrent.Promise
import com.campudus.vertx.TestVerticle
import scala.util.Success
import scala.util.Failure
import org.vertx.java.core.eventbus.Message

class RegistrationTester extends TestVerticle {

  lazy val validDownloadUrl: String = "https://oss.sonatype.org/content/groups/public/com/campudus/session-manager/2.0.0-beta0/session-manager-2.0.0-beta0.zip"
  lazy val incorrectDownloadUrl: String = "http://asdfreqw/"

  override def start() {
    container.deployModule(System.getProperty("vertx.modulename"), new Handler[AsyncResult[String]]() {
      override def handle(deploymentID: AsyncResult[String]) {
        assertNotNull("deploymentID should not be null", deploymentID)

        initialize()
        resetMongoDb map (_ => startTests()) recover {
          case ex =>
            fail(ex.getMessage() + " - " + ex.getCause())
        }
      }
    })
  }

  @Test
  def testRegisterMod() {
    registerModule(validDownloadUrl) onComplete {
      case Success(data) => Option(data.getString("status")) match {
        case Some("ok") => testComplete()
        case _ => fail("wrong status / error reply: " + data.encode())
      }
      case Failure(ex) => fail("should not get exception, but got " + ex)
    }
  }

  @Test
  def testRegisterModTwice() {
    registerModule(validDownloadUrl) flatMap (_ => registerModule(validDownloadUrl)) onComplete {
      case Success(data) => Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case _ => fail("should get an error reply, but got " + data.encode())
      }
      case Failure(ex) => fail("Should get an error reply, not a failed future")
    }
  }

  private def registerModule(modUrl: String) = {
    val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
    noExceptionInClient(client)

    postJson(client, "/register", "downloadUrl", modUrl)
  }
  private def noExceptionInClient(client: HttpClient) = client.exceptionHandler({ ex: Throwable =>
    fail("Should not get an exception in this test, but got " + ex)
  })

  private def postJson(client: HttpClient, url: String, param: String, data: String): Future[JsonObject] = {
    val p = Promise[JsonObject]
    val request = client.post(url, { resp: HttpClientResponse =>
      resp.bodyHandler({ buf: Buffer =>
        try {
          p.success(new JsonObject(buf.toString()))
        } catch {
          case e: Throwable => p.failure(e)
        }
      })
    })
    request.end(param + "=" + data)
    p.future
  }

  private def getJson(client: HttpClient, url: String): Future[JsonObject] = {
    val p = Promise[JsonObject]
    val request = client.get(url, { resp: HttpClientResponse =>
      resp.bodyHandler({ buf: Buffer =>
        try {
          p.success(new JsonObject(buf.toString()))
        } catch {
          case e: Throwable => p.failure(e)
        }
      })
    })
    p.future
  }

  private def resetMongoDb(): Future[Integer] = {
    val p = Promise[Integer]
    vertx.eventBus().send("registry.database", json
      .putString("action", "delete")
      .putString("collection", "modules")
      .putObject("matcher", json), {
      reply: Message[JsonObject] =>
        if ("ok" == reply.body.getString("status")) {
          p.success(reply.body.getInteger("number", 0))
        } else {
          p.failure(new RuntimeException("could not reset mongodb"))
        }
    })
    p.future
  }
}