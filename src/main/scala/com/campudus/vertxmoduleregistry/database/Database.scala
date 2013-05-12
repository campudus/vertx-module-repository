package com.campudus.vertxmoduleregistry.database

import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.Vertx
import org.vertx.java.core.eventbus.Message
import com.campudus.vertx.helpers.VertxScalaHelpers
import scala.concurrent.Promise
import scala.concurrent.Future
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
    description: String,
    license: String,
    projectUrl: String,
    keywords: List[String],
    author: String,
    contributors: Option[List[String]] = None,
    timeRegistered: Long,
    timeApproved: Long = -1,
    approved: Boolean = false,
    id: String = UUID.randomUUID.toString) {

    def toJson(): JsonObject = {
      val js = json.putString("_id", id)
        .putString("downloadUrl", downloadUrl)
        .putString("name", name)
        .putString("description", description)
        .putString("license", license)
        .putString("projectUrl", projectUrl)
        .putArray("keywords", stringListToArray(keywords))
        .putString("author", author)
        .putNumber("timeRegistered", timeRegistered)
        .putNumber("timeApproved", timeApproved)
        .putBoolean("approved", approved)

      // Optional fields
      contributors.map { contribs => js.putArray("contributors", stringListToArray(contribs)) }

      js
    }
  }

  object Module {
    def fromModJson(obj: JsonObject): Option[Module] = tryOp {
      val downloadUrl = obj.getString("downloadUrl")
      val name = obj.getString("name")
      val description = obj.getString("description")
      val license = obj.getString("license")
      val projectUrl = obj.getString("projectUrl")
      val keywords = jsonArrayToStringList(obj.getArray("keywords"))
      val author = obj.getString("author")
      val contributors = Option(obj.getArray("contributors")) map jsonArrayToStringList

      Module(downloadUrl, name, description, license, projectUrl, keywords, author, contributors, System.currentTimeMillis())
    }

    def fromMongoJson(obj: JsonObject): Module = fromModJson(obj).get
  }

  def searchModules(vertx: Vertx, search: String): Future[List[Module]] = {
    val searchRegexObj = json.putString("$regex", search)
    val listOfFields = List("downloadUrl", "name", "description", "license", "projectUrl", "keywords", "author", "contributors")
    val arr = new JsonArray
    listOfFields map (json.putObject(_, searchRegexObj)) foreach (arr.addObject)

    val searchJson = json
      .putArray("$or", arr)
      .putBoolean("approved", true)

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
              val modules = msg.body.getArray("results").map {
                case m: JsonObject => Module.fromMongoJson(m)
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
              val modules = msg.body.getArray("results").map {
                case m: JsonObject => Module.fromMongoJson(m)
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
              val modules = msg.body.getArray("results").map {
                case m: JsonObject => Module.fromMongoJson(m)
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