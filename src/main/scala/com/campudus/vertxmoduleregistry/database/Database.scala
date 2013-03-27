package com.campudus.vertxmoduleregistry.database

import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.Vertx
import org.vertx.java.core.eventbus.Message
import com.campudus.vertx.helpers.VertxScalaHelpers
import scala.concurrent.Promise

object Database extends VertxScalaHelpers {

  val dbAddress = "registry.database"

  case class Module(downloadUrl: String, name: String, owner: String, version: String, vertxVersion: String, description: String, projectUrl: String, author: String, email: String, license: String, keywords: List[String], approved: Boolean = false) {
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
      .putBoolean("approved", approved)
  }

  def searchModules(vertx: Vertx, search: String) = {
    val searchJson = json.
      putArray("$or", new JsonArray()
        .addObject(json.putObject("description", json.putString("$regex", search))))

    println("searching for " + searchJson.encode())
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