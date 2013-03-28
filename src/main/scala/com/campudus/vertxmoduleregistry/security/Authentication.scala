package com.campudus.vertxmoduleregistry.security

import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.Vertx
import scala.concurrent._
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonObject
import scala.None

object Authentication extends VertxScalaHelpers {

  val authAddress = "registry.auth"

  def login(vertx: Vertx, username: String, password: String): Future[String] = {
    val p = Promise[String]

    vertx.eventBus().send(authAddress + ".login", json.putString("username", username).putString("password", password), { msg: Message[JsonObject] =>
      msg.body.getString("status") match {
        case "ok" => p.success(msg.body.getString("sessionID"))
        case "denied" => p.failure(new AuthenticationException("Login failed"))
      }
    })

    p.future
  }

  def logout(vertx: Vertx, sessionId: String): Future[String] = {
    val p = Promise[String]

    vertx.eventBus().send(authAddress + ".logout", json.putString("sessionId", sessionId), { msg: Message[JsonObject] =>
      msg.body.getString("status") match {
        case "ok" => p.success(sessionId)
        case "error" => p.failure(new AuthenticationException("Logout failed"))
      }
    })

    p.future
  }

  def authorise(vertx: Vertx, sessionId: String): Future[String] = {
    val p = Promise[String]

    vertx.eventBus().send(authAddress + ".authorise", json.putString("sessionId", sessionId), { msg: Message[JsonObject] =>
      msg.body.getString("status") match {
        case "ok" => p.success(msg.body.getString("username"))
        case "denied" => p.failure(new AuthenticationException("Authorization failed"))
      }
    })

    p.future
  }

}