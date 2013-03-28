package com.campudus.vertxmoduleregistry.database

import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.Vertx
import org.vertx.java.core.eventbus.Message
import com.campudus.vertx.helpers.VertxScalaHelpers
import scala.concurrent.Promise
import scala.concurrent._

object Database extends VertxScalaHelpers {

  val dbAddress = "registry.database"

  case class Module(downloadUrl: String, name: String, owner: String, version: String, vertxVersion: String, description: String, projectUrl: String, author: String, email: String, license: String, keywords: List[String], timeRegistered: Long, timeApproved: Long = -1, approved: Boolean = false) {
    def toJson(): JsonObject = json
      .putString("downloadUrl", downloadUrl)
      .putString("name", name)
      .putString("owner", owner)
      .putString("version", version)
      .putString("vertxVersion", vertxVersion)
      .putString("description", description)
      .putString("projectUrl", projectUrl)
      .putString("author", author)
      .putString("email", email)
      .putString("license", license)
      .putArray("keywords", {
        val arr = new JsonArray()
        keywords.foreach(k => arr.addString(k))
        arr
      })
      .putNumber("timeRegistered", timeRegistered)
      .putNumber("timeApproved", timeApproved)
      .putBoolean("approved", approved)
  }
  object Module {
    def fromJson(json: JsonObject): Option[Module] = tryOp {
      val downloadUrl = json.getString("downloadUrl")
      val modname = json.getString("name")
      val modowner = json.getString("owner")
      val version = json.getString("version")
      val vertxVersion = json.getString("vertxVersion")
      val description = json.getString("description")
      val projectUrl = json.getString("projectUrl")
      val author = json.getString("author")
      val email = json.getString("email")
      val license = json.getString("license")
      val keywords = (for (k <- json.getArray("keywords").toArray()) yield k.toString()).toList
      val timeRegistered = json.getLong("timeRegistered")
      val timeApproved = json.getLong("timeRegistered")
      val approved = json.getBoolean("approved")

      Module(downloadUrl, modname, modowner, version, vertxVersion, description, projectUrl, author, email, license, keywords, timeRegistered, timeApproved, approved)
    }
  }

  def searchModules(vertx: Vertx, search: String): Future[List[Module]] = {
    val searchJson = json.
      putArray("$or", new JsonArray()
        .addObject(json.putObject("downloadUrl", json.putString("$regex", search)))
        .addObject(json.putObject("name", json.putString("$regex", search)))
        .addObject(json.putObject("owner", json.putString("$regex", search)))
        .addObject(json.putObject("description", json.putString("$regex", search)))
        .addObject(json.putObject("projectUrl", json.putString("$regex", search)))
        .addObject(json.putObject("author", json.putString("$regex", search)))
        .addObject(json.putObject("email", json.putString("$regex", search)))
        .addObject(json.putObject("keywords", json.putString("$regex", search))))

    println("Searching for with: " + searchJson.encode())

    val p = Promise[List[Module]]

    vertx.eventBus().send(dbAddress,
      json
        .putString("action", "find")
        .putString("collection", "modules")
        .putObject("matcher", searchJson), {
        msg: Message[JsonObject] =>
          msg.body.getString("status") match {
            case "ok" =>
              import scala.collection.JavaConversions._
              val modules = msg.body.getArray("results").flatMap {
                m =>
                  m match {
                    case m: JsonObject => Module.fromJson(m) match {
                      case Some(someMod) => List(someMod)
                      case None => List()
                    }
                  }
              }
              p.success(modules.toList)

            case "error" => p.failure(new DatabaseException(msg.body.getString("message")))
          }
      })

    p.future
  }

  def latestApprovedModules(vertx: Vertx, limit: Int): Future[List[Module]] = {
    val p = Promise[List[Module]]
    vertx.eventBus().send(dbAddress,
      json
        .putString("action", "find")
        .putString("collection", "modules")
        .putNumber("limit", limit)
        .putObject("sort", json.putNumber("timeApproved", -1))
        .putObject("matcher", json.putBoolean("approved", true)), {
        msg: Message[JsonObject] =>
          msg.body.getString("status") match {
            case "ok" =>
              import scala.collection.JavaConversions._
              val it = msg.body.getArray("results").iterator()
              val modules = msg.body.getArray("results").flatMap {
                m =>
                  m match {
                    case m: JsonObject => Module.fromJson(m) match {
                      case Some(someMod) => List(someMod)
                      case None => List()
                    }
                  }
              }
              p.success(modules.toList)

            case "error" => p.failure(new DatabaseException(msg.body.getString("message")))
          }
      })

    p.future
  }

  def unapproved(vertx: Vertx): Future[List[Module]] = {
    val p = Promise[List[Module]]
    vertx.eventBus().send(dbAddress,
      json
        .putString("action", "find")
        .putString("collection", "modules")
        .putObject("sort", json.putNumber("timeRegistered", 1))
        .putObject("matcher", json.putBoolean("approved", false)), {
        msg: Message[JsonObject] =>
          msg.body.getString("status") match {
            case "ok" =>
              import scala.collection.JavaConversions._
              val it = msg.body.getArray("results").iterator()
              val modules = msg.body.getArray("results").flatMap {
                m =>
                  m match {
                    case m: JsonObject => Module.fromJson(m) match {
                      case Some(someMod) => List(someMod)
                      case None => List()
                    }
                  }
              }
              p.success(modules.toList)

            case "error" => p.failure(new DatabaseException(msg.body.getString("message")))
          }
      })

    p.future
  }

  def approve(vertx: Vertx, id: String) = {
    val p = Promise[JsonObject]
    vertx.eventBus().send(dbAddress,
      json
        .putString("action", "update")
        .putString("collection", "modules")
        .putObject("criteria", json.putString("_id", id))
        .putObject("objNew", json.putObject("$set", json.putBoolean("approved", true))), {
        msg: Message[JsonObject] =>
          msg.body.getString("status") match {
            case "ok" => p.success(json.putString("status", "ok").putString("id", id))
            case "error" => p.failure(new DatabaseException(msg.body.getString("message")))
          }
      })
    p.future
  }

  def registerModule(vertx: Vertx, module: Module) = {
    val p = Promise[JsonObject]
    vertx.eventBus().send(dbAddress,
      json
        .putString("action", "save")
        .putString("collection", "modules")
        .putObject("document", module.toJson), {
        msg: Message[JsonObject] =>
          msg.body.getString("status") match {
            case "ok" => p.success(msg.body)
            case "error" => p.failure(new DatabaseException(msg.body.getString("message")))
          }
      })
    p.future
  }

}