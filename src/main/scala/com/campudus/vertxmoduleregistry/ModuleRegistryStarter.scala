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
import scala.concurrent.duration._
import scala.concurrent.Await

class ModuleRegistryStarter extends Verticle with VertxScalaHelpers {
  import ModuleRegistryStarter._

  override def start(startedResult: org.vertx.java.core.Future[Void]) {
    lazy val logger = container.logger()

    logger.info("Starting module registry ...")

    val config = Option(container.config()).getOrElse(json)
    val configDb = config.getObject("database", json)
      .putString("address", Database.dbAddress)
    val configMailer = Option(config.getObject("mailer")) match {
      case Some(obj) => obj.putString("address", mailerAddress)
      case None => null
    }
    val configAuth = config.getObject("auth", json.putString("db_name", "moduleregistry"))
      .putString("persistor_address", Database.dbAddress)
      .putString("address", Authentication.authAddress)
    val configUnzip = config.getObject("unzip", json)
      .putString("address", unzipAddress)

    logger.info("deploying all modules with " + config)

    val modFuture = deployModule(mongoPersistorModName, configDb)
      .map(id => println("deployed mongo persistor with id: " + id))
      .flatMap(_ => if (configMailer != null) {
        deployModule(mailerModName, configMailer)
      } else {
        Future.successful("-id-")
      })
      .map(id => println("deployed mailer with id: " + id))
      .flatMap(_ => deployModule(authManagerModName, configAuth))
      .map(id => println("deployed auth manager with id: " + id))
      .flatMap(_ => deployModule(unzipModName, configUnzip))
      .map(id => println("deployed unzip module with id: " + id))
      .flatMap(_ => deployVerticle(serverVerticle, config))
      .map(id => println("deployed verticle with id: " + id))

    logger.info("Modules should deploy async now.")

    modFuture.map { _ =>
      logger.info("started, setting result")
      startedResult.setResult(null)
    }
  }

  override def stop() {
    logger.info("Module registry stopped.")
  }

  private def deployVerticle(name: String, config: JsonObject): Future[String] = {
    val p = Promise[String]
    container.deployVerticle(name, config, { deployResult: AsyncResult[String] =>
      if (deployResult.succeeded()) {
        println("started " + name + " with config " + config)
        p.success(deployResult.result())
      } else {
        println("failed to start " + name + " because of " + deployResult.cause())
        deployResult.cause().printStackTrace()
        p.failure(deployResult.cause())
      }
    })
    p.future
  }

  private def deployModule(name: String, config: JsonObject): Future[String] = {
    val p = Promise[String]
    container.deployModule(name, config, { deployResult: AsyncResult[String] =>
      if (deployResult.succeeded()) {
        println("started " + name + " with config " + config)
        p.success(deployResult.result())
      } else {
        println("failed to start " + name + " because of " + deployResult.cause())
        deployResult.cause().printStackTrace()
        p.failure(deployResult.cause())
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