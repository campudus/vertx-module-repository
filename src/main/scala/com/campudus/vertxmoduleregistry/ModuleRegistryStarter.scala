package com.campudus.vertxmoduleregistry

import org.vertx.java.platform.Verticle
import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.json.JsonObject
import com.campudus.vertxmoduleregistry.database.Database

class ModuleRegistryStarter extends Verticle with VertxScalaHelpers {

  val mongoPersistorModName = "io.vertx~mod-mongo-persistor~2.0.0-SNAPSHOT"

  override def start() {
    /*{
    "address": <address>,
    "host": <host>,
    "port": <port>,
    "db_name": <db_name>,
    "fake": <fake>
}*/
    val config = new JsonObject()
      .putString("address", Database.dbAddress)
      .putString("host", "localhost")
      .putNumber("port", 27017)
      .putString("db_name", "module_registry")
//      .putBoolean("fake", true) // FIXME delete this when deploying on openshift!
    container.deployModule(mongoPersistorModName, config, { deploymentId: String =>
      container.deployVerticle("com.campudus.vertxmoduleregistry.ModuleRegistryServer", {
        deploymentId2: String =>
          println("Module registry started")
      })
    })
  }

  override def stop() {
    println("Module registry stopped.")
  }
}