package com.campudus.vertxmoduleregistry.security

import scala.concurrent.Future
import scala.concurrent.Promise

import org.vertx.java.core.Vertx
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.json.JsonObject

import com.campudus.vertx.helpers.VertxScalaHelpers

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

  def logout(vertx: Vertx, sessionID: String): Future[String] = {
    val p = Promise[String]

    vertx.eventBus().send(authAddress + ".logout", json.putString("sessionID", sessionID), { msg: Message[JsonObject] =>
      msg.body.getString("status") match {
        case "ok" => p.success(sessionID)
        case "error" => p.failure(new AuthenticationException("Logout failed"))
      }
    })

    p.future
  }

  def authorise(vertx: Vertx, sessionID: String): Future[String] = {
    val p = Promise[String]
    vertx.eventBus().send(authAddress + ".authorise", json.putString("sessionID", sessionID), { msg: Message[JsonObject] =>
      msg.body.getString("status") match {
        case "ok" => p.success(msg.body.getString("username"))
        case "denied" => p.failure(new AuthenticationException("Authorization failed"))
      }
    })
    p.future
  }

}