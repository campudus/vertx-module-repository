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

class ModuleRegistryTester extends TestVerticle {

  val validDownloadUrl: String = "https://oss.sonatype.org/content/groups/public/com/campudus/session-manager/2.0.0-beta0/session-manager-2.0.0-beta0.zip"
  val incorrectDownloadUrl: String = "http://asdfreqw/"
  val approverPw: String = "password"

  override def start() {
    container.deployModule(System.getProperty("vertx.modulename"),
      json.putString("approver-password", approverPw),
      new Handler[AsyncResult[String]]() {
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

  @Test
  def testDeleteModule() {
    registerModule(validDownloadUrl) flatMap { obj =>
      val modName = obj.getObject("data").getString("name")
      println("registered " + modName + ", now delete it" + obj.encode)
      deleteModule(modName)
    } onComplete {
      case Success(data) => Option(data.getString("status")) match {
        case Some("ok") => testComplete()
        case _ => fail("wrong status on delete! " + data.encode)
      }
      case Failure(ex) => fail("Should not get an exception but got " + ex)
    }
  }

  @Test
  def testDeleteWithoutPassword() {
    registerModule(validDownloadUrl) flatMap { obj =>
      val modId = obj.getObject("data").getString("id")
      val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
      noExceptionInClient(client)
      postJson(client, "/remove", "name" -> modId)
    } onComplete {
      case Success(data) => Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case _ => fail("Should not be able to delete a module id twice")
      }
      case Failure(ex) => fail("Should not get an exception but got " + ex)
    }
  }

  @Test
  def testDeleteModuleTwice() {
    registerModule(validDownloadUrl) flatMap { obj =>
      val modId = obj.getObject("data").getString("id")
      deleteModule(modId) flatMap (_ => deleteModule(modId))
    } onComplete {
      case Success(data) => Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case _ => fail("Should not be able to delete a module id twice")
      }
      case Failure(ex) => fail("Should not get an exception but got " + ex)
    }
  }

  @Test
  def testDeleteMissingModule() {
    deleteModule("some-missing-id") onComplete {
      case Success(data) => Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case x => fail("Should not be able to delete a module that doesn't exist " + x)
      }
      case Failure(ex) => fail("Should not get an exception but got " + ex)
    }
  }

  private def deleteModule(modName: String) = {
    val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
    noExceptionInClient(client)

    postJson(client, "/login", "password" -> approverPw) flatMap { obj =>
      val sessionId = obj.getString("sessionID")
      println("got login " + obj.encode)
      postJson(client, "/remove", "sessionID" -> sessionId, "name" -> modName)
    }
  }

  private def registerModule(modUrl: String) = {
    val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
    noExceptionInClient(client)

    postJson(client, "/register", "downloadUrl" -> modUrl)
  }

  private def noExceptionInClient(client: HttpClient) = client.exceptionHandler({ ex: Throwable =>
    fail("Should not get an exception in this test, but got " + ex)
  })

  private def postJson(client: HttpClient, url: String, params: (String, String)*): Future[JsonObject] = {
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
    request.end(params.map { case (key, value) => key + "=" + value }.mkString("&"))
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
      .putObject("matcher", json), { replyReset: Message[JsonObject] =>
      if ("ok" == replyReset.body.getString("status")) {
        p.success(replyReset.body.getInteger("number", 0))
      } else {
        p.failure(new RuntimeException("could not reset mongodb"))
      }
    })
    p.future
  }
}