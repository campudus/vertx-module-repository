package com.campudus.vertxmoduleregistry

import org.vertx.java.platform.Verticle
import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.json.JsonObject
import com.campudus.vertxmoduleregistry.database.Database
import com.campudus.vertxmoduleregistry.security.Authentication

class ModuleRegistryStarter extends Verticle with VertxScalaHelpers {

  val mongoPersistorModName = "io.vertx~mod-mongo-persistor~2.0.0-SNAPSHOT"
  val authManagerModName = "io.vertx~mod-auth-mgr~2.0.0-SNAPSHOT"

  override def start() {

    val config = container.getConfig()
    val configDb = config.getObject("database")
      .putString("address", Database.dbAddress)
    val configAuth = config.getObject("auth")
      .putString("persistor_address", Database.dbAddress)
      .putString("address", Authentication.authAddress)

    container.deployModule(mongoPersistorModName, configDb, { deploymentId: String =>
      container.deployModule(authManagerModName, configAuth, { deploymentId2: String =>
        container.deployVerticle("com.campudus.vertxmoduleregistry.ModuleRegistryServer", config, {
          deploymentId3: String =>
            println("Module registry started")
        })
      })

    })
  }

  override def stop() {
    println("Module registry stopped.")
  }
}