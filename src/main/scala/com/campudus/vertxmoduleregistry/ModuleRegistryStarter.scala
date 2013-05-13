package com.campudus.vertxmoduleregistry

import scala.concurrent.{ Future, Promise }
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.json.JsonObject
import com.campudus.vertx.helpers.VertxScalaHelpers
import com.campudus.vertxmoduleregistry.database.Database
import com.campudus.vertxmoduleregistry.security.Authentication
import org.vertx.java.core.AsyncResultHandler
import org.vertx.java.core.Handler
import com.campudus.vertx.Verticle

class ModuleRegistryStarter extends Verticle with VertxScalaHelpers {
  import ModuleRegistryStarter._

  override def start() {
    lazy val logger = container.logger()

    logger.error("Starting module registry ...")

    val config = Option(container.config()).getOrElse(new JsonObject)
    val configDb = config.getObject("database", new JsonObject)
      .putString("address", Database.dbAddress)
    val configMailer = config.getObject("mailer", new JsonObject)
      .putString("address", mailerAddress)
    val configAuth = config.getObject("auth", new JsonObject)
      .putString("persistor_address", Database.dbAddress)
      .putString("address", Authentication.authAddress)
    val configUnzip = config.getObject("unzip", new JsonObject)
      .putString("address", unzipAddress)

    println("deploying all modules with " + config)

    deployModule(mongoPersistorModName, configDb)
      .map(id => println("deployed mongo persistor with id: " + id))
      .flatMap(_ => deployModule(mailerModName, configMailer))
      .map(id => println("deployed mailer with id: " + id))
      .flatMap(_ => deployModule(authManagerModName, configAuth))
      .map(id => println("deployed auth manager with id: " + id))
      .flatMap(_ => deployModule(unzipModName, configUnzip))
      .map(id => println("deployed unzip module with id: " + id))
      .flatMap(_ => deployVerticle(serverVerticle, config))
      .map(id => println("deployed verticle with id: " + id))

    println("Modules should deploy async now.")

  }

  override def stop() {
    println("Module registry stopped.")
  }

  private def deployVerticle(name: String, config: JsonObject): Future[String] = {
    val p = Promise[String]
    container.deployVerticle(name, config, new AsyncResultHandler[String]() {
      def handle(deployResult: AsyncResult[String]) = {
        if (deployResult.succeeded()) {
          println("started " + name + " with config " + config)
          p.success(deployResult.result())
        } else {
          println("failed to start " + name + " because of " + deployResult.cause())
          deployResult.cause().printStackTrace()
          p.failure(deployResult.cause())
        }
      }
    })
    p.future
  }

  private def deployModule(name: String, config: JsonObject): Future[String] = {
    val p = Promise[String]
    container.deployModule(name, config, new AsyncResultHandler[String]() {
      def handle(deployResult: AsyncResult[String]) = {
        if (deployResult.succeeded()) {
          println("started " + name + " with config " + config)
          p.success(deployResult.result())
        } else {
          println("failed to start " + name + " because of " + deployResult.cause())
          p.failure(deployResult.cause())
        }
      }
    })
    p.future
  }
}

object ModuleRegistryStarter {
  val mongoPersistorModName = "io.vertx~mod-mongo-persistor~2.0.0-SNAPSHOT"
  val mailerModName = "io.vertx~mod-mailer~2.0.0-SNAPSHOT"
  val mailerAddress = "io.vertx.mailer"
  val authManagerModName = "io.vertx~mod-auth-mgr~2.0.0-SNAPSHOT"
  val unzipModName = "io.vertx~mod-unzip~1.0.0-SNAPSHOT"
  val unzipAddress = "io.vertx.unzipper"
  val serverVerticle = "com.campudus.vertxmoduleregistry.ModuleRegistryServer"
}