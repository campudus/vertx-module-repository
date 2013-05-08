package com.campudus.vertxmoduleregistry

import org.vertx.java.platform.Verticle
import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.json.JsonObject
import com.campudus.vertxmoduleregistry.database.Database
import com.campudus.vertxmoduleregistry.security.Authentication
import org.vertx.java.core.AsyncResult
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits._

class ModuleRegistryStarter extends Verticle with VertxScalaHelpers {

  val mongoPersistorModName = "io.vertx~mod-mongo-persistor~2.0.0-SNAPSHOT"
  val authManagerModName = "io.vertx~mod-auth-mgr~2.0.0-SNAPSHOT"
  val unzipModName = "io.vertx~mod-unzip~1.0.0-SNAPSHOT"
  val unzipAddress = "io.vertx.unzipper"

  override def start() {
    lazy val logger = container.logger()

    logger.error("Starting module registry ...")

    val config = Option(container.config()).getOrElse(json)
    val configDb = config.getObject("database", json)
      .putString("address", Database.dbAddress)
    val configAuth = config.getObject("auth", json)
      .putString("persistor_address", Database.dbAddress)
      .putString("address", Authentication.authAddress)
    val configUnzip = config.getObject("unzip", json)
      .putString("address", unzipAddress)

    def handleDeployError(onSuccess: String => Unit) = { deployResult: AsyncResult[String] =>
      if (deployResult.succeeded()) {
        onSuccess(deployResult.result())
      } else {
        logger.error("failed to start verticle " + deployResult.cause())
        deployResult.cause().printStackTrace()
      }
    }

    for {
      mongoPersistor <- deployModule(mongoPersistorModName, configDb)
      authManager <- deployModule(mongoPersistorModName, configAuth)
      unzipModule <- deployModule(unzipModName, configUnzip)
    } {
      logger.error("deploying ModuleRegistryServer with " + config)
      container.deployVerticle("com.campudus.vertxmoduleregistry.ModuleRegistryServer", config,
        handleDeployError { deploymentId: String =>
          logger.error("Module registry started")
        })
    }

    logger.info("Modules should deploy async now.")
  }

  override def stop() {
    println("Module registry stopped.")
  }

  private def deployModule(name: String, config: JsonObject): Future[String] = {
    val p = Promise[String]
    container.deployModule(name, config, { deployResult: AsyncResult[String] =>
      if (deployResult.succeeded()) {
        p.success(deployResult.result())
      } else {
        println("failed to start " + name + " because of " + deployResult.cause())
        p.failure(deployResult.cause())
      }
    })
    p.future
  }
}