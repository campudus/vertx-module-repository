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
import scala.util.Try

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
    registerModule(validDownloadUrl) onComplete handleFailure { data =>
      Option(data.getString("status")) match {
        case Some("ok") => testComplete()
        case _ => fail("wrong status / error reply: " + data.encode())
      }
    }
  }

  @Test
  def testRegisterModTwice() {
    registerModule(validDownloadUrl) flatMap (_ => registerModule(validDownloadUrl)) onComplete handleFailure { data =>
      Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case _ => fail("should get an error reply, but got " + data.encode())
      }
    }
  }

  @Test
  def testDeleteModule() {
    registerModule(validDownloadUrl) flatMap { obj =>
      val modName = obj.getObject("data").getString("name")
      println("registered " + modName + ", now delete it" + obj.encode)
      deleteModule(modName)
    } onComplete handleFailure { data =>
      Option(data.getString("status")) match {
        case Some("ok") => testComplete()
        case _ => fail("wrong status on delete! " + data.encode)
      }
    }
  }

  @Test
  def testDeleteWithoutPassword() {
    registerModule(validDownloadUrl) flatMap { obj =>
      val modId = obj.getObject("data").getString("id")
      val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
      noExceptionInClient(client)
      postJson(client, "/remove", "name" -> modId)
    } onComplete handleFailure { data =>
      Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case _ => fail("Should not be able to delete a module id twice")
      }
    }
  }

  @Test
  def testDeleteModuleTwice() {
    registerModule(validDownloadUrl) flatMap { obj =>
      val modId = obj.getObject("data").getString("id")
      deleteModule(modId) flatMap (_ => deleteModule(modId))
    } onComplete handleFailure { data =>
      Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case _ => fail("Should not be able to delete a module id twice")
      }
    }
  }

  @Test
  def testDeleteMissingModule() {
    deleteModule("some-missing-id") onComplete handleFailure { data =>
      Option(data.getString("status")) match {
        case Some("error") => testComplete()
        case x => fail("Should not be able to delete a module that doesn't exist " + x)
      }
    }
  }

  @Test
  def testListAllModules() {
    registerModule(validDownloadUrl) flatMap (_ => registerModule(validDownloadUrl)) flatMap { _ =>
      val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
      noExceptionInClient(client)

      getJson(client, "/list")
    } onComplete handleFailure { obj =>
      Option(obj.getArray("modules")) match {
        case Some(results) => testComplete()
        case None => fail("should get results but got none")
      }
    }
  }

  @Test
  def testLogin() {
    val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
    noExceptionInClient(client)

    postJson(client, "/login", "password" -> approverPw) map { obj =>
      assertNotNull("Should receive a working session id", obj.getString("sessionID"))
      testComplete()
    }
  }

  @Test
  def testLogout() {
    val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
    noExceptionInClient(client)

    postJson(client, "/login", "password" -> approverPw) flatMap { obj =>
      val sessionId = obj.getString("sessionID")
      assertNotNull("Should receive a working session id", sessionId)
      postJson(client, "/logout", "sessionID" -> sessionId)
    } onComplete handleFailure { res =>
      Option(res.getString("status")) match {
        case Some("ok") => testComplete()
        case _ => fail("got an error logging out!" + res.encode)
      }
    }
  }

  @Test
  def testLogoutWithWrongSessionId() {
    val client = vertx.createHttpClient().setHost("localhost").setPort(8080)
    noExceptionInClient(client)

    postJson(client, "/logout", "sessionID" -> "wrong-session-id") map { res =>
      Option(res.getString("status")) match {
        case Some("ok") => fail("Should not be able to logout with wrong session-id! " + res.encode)
        case Some("error") => testComplete()
        case _ => fail("Should get a status reply but got " + res.encode)
      }
    }
  }

  private def handleFailure[T](doSth: T => Unit): Function1[Try[T], Any] = {
    case Success(x) => doSth(x)
    case Failure(x) => fail("Should not get an exception but got " + x)
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

  private def registerModule(modUrl: String): Future[JsonObject] = {
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

  private def getJson(client: HttpClient, url: String, params: (String, String)*): Future[JsonObject] = {
    val p = Promise[JsonObject]
    val request = client.get(url + params.map { case (key, value) => key + "=" + value }.mkString("?", "&", ""), { resp: HttpClientResponse =>
      resp.bodyHandler({ buf: Buffer =>
        try {
          p.success(new JsonObject(buf.toString()))
        } catch {
          case e: Throwable => p.failure(e)
        }
      })
    })
    request.end()
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