package com.campudus.vertxmoduleregistry.database

import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.Vertx
import org.vertx.java.core.eventbus.Message
import com.campudus.vertx.helpers.VertxScalaHelpers
import scala.concurrent.Promise
import scala.concurrent._
import java.util.UUID

object Database extends VertxScalaHelpers {
  import scala.collection.JavaConverters._

  val dbAddress = "registry.database"
  /*
[16:59:32] <purplefox>   name - module identifier e.g. io.vertx~my-mod~1.0 
[16:59:33] <purplefox>   description - text description
[16:59:33] <purplefox>   license or licenses - JSON array
[16:59:33] <purplefox>   homepage - url to homepage of project
[16:59:33] <purplefox>   keywords - for search
[16:59:33] <purplefox>   author - individual or organisation
[16:59:35] <purplefox>   contributors - optional, array
[16:59:37] <purplefox>   repository - url to repository - e.g. github url
       */

  private def stringListToArray(list: List[String]): JsonArray = {
    val arr = new JsonArray()
    list.foreach(k => arr.addString(k))
    arr
  }

  private def jsonArrayToStringList(arr: JsonArray): List[String] = {
    (for (elem <- arr.toArray()) yield elem.toString).toList
  }

  case class Module(
    downloadUrl: String,
    name: String,
    owner: String,
    version: String,
    vertxVersion: String,
    description: String,
    projectUrl: String,
    author: String,
    email: String,
    licenses: List[String],
    keywords: List[String],
    timeRegistered: Long,
    timeApproved: Long = -1,
    contributors: Option[List[String]] = None,
    approved: Boolean = false,
    id: String = UUID.randomUUID.toString) {

    def toJson(): JsonObject = {
      val js = json.putString("_id", id)
        .putString("downloadUrl", downloadUrl)
        .putString("name", name)
        .putString("owner", owner)
        .putString("version", version)
        .putString("vertxVersion", vertxVersion)
        .putString("description", description)
        .putString("projectUrl", projectUrl)
        .putString("author", author)
        .putString("email", email)
        .putArray("licenses", stringListToArray(licenses))
        .putArray("keywords", stringListToArray(keywords))
        .putNumber("timeRegistered", timeRegistered)
        .putNumber("timeApproved", timeApproved)
        .putBoolean("approved", approved)

      // Optional fields
      contributors.map { contribs => js.putArray("contributors", stringListToArray(contribs)) }

      js
    }
  }

  object Module {
    def fromJson(json: JsonObject): Option[Module] = tryOp {
      val id = json.getString("_id")
      val downloadUrl = json.getString("downloadUrl")
      val modname = json.getString("name")
      val modowner = json.getString("owner")
      val version = json.getString("version")
      val vertxVersion = json.getString("vertxVersion")
      val description = json.getString("description")
      val projectUrl = json.getString("projectUrl")
      val author = json.getString("author")
      val email = json.getString("email")
      val licenses = jsonArrayToStringList(json.getArray("licenses"))
      val keywords = jsonArrayToStringList(json.getArray("keywords"))
      val timeRegistered = json.getLong("timeRegistered")
      val timeApproved = json.getLong("timeApproved")
      val contibutors = Option(json.getArray("timeApproved")).map(jsonArrayToStringList(_))
      val approved = json.getBoolean("approved")

      Module(downloadUrl, modname, modowner, version, vertxVersion, description, projectUrl, author, email, licenses, keywords, timeRegistered, timeApproved, contibutors, approved, id)
    }
  }

  def searchModules(vertx: Vertx, search: String): Future[List[Module]] = {
    val searchJson = json.putArray("$or", new JsonArray()
      .addObject(json.putObject("downloadUrl", json.putString("$regex", search)))
      .addObject(json.putObject("name", json.putString("$regex", search)))
      .addObject(json.putObject("owner", json.putString("$regex", search)))
      .addObject(json.putObject("description", json.putString("$regex", search)))
      .addObject(json.putObject("projectUrl", json.putString("$regex", search)))
      .addObject(json.putObject("author", json.putString("$regex", search)))
      .addObject(json.putObject("email", json.putString("$regex", search)))
      .addObject(json.putObject("keywords", json.putString("$regex", search)))).putBoolean("approved", true)

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

  def approve(vertx: Vertx, id: String): Future[JsonObject] = {
    val p = Promise[JsonObject]
    vertx.eventBus().send(dbAddress,
      json
        .putString("action", "update")
        .putString("collection", "modules")
        .putObject("criteria", json.putString("_id", id))
        .putObject("objNew", json.putObject("$set", json.putBoolean("approved", true))), {
        msg: Message[JsonObject] =>
          msg.body.getString("status") match {
            case "ok" => p.success(json.putString("status", "ok").putString("_id", id))
            case "error" => p.failure(new DatabaseException(msg.body.getString("message")))
          }
      })
    p.future
  }

  def registerModule(vertx: Vertx, module: Module): Future[JsonObject] = {
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