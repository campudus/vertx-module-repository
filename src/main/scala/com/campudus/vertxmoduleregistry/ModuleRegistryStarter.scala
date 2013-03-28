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
    /*{
    "address": <address>,
    "host": <host>,
    "port": <port>,
    "db_name": <db_name>,
    "fake": <fake>
}*/
    val configDb = new JsonObject()
      .putString("address", Database.dbAddress)
      .putString("host", "localhost")
      .putNumber("port", 27017)
      .putString("db_name", "module_registry")
    //      .putBoolean("fake", true) // FIXME delete this when deploying on openshift!

    /* "address": "test.my_authmgr",
    "user_collection": "users",
    "persistor_address": "test.my_persistor",
    "session_timeout": 900000 */

    val configAuth = new JsonObject()
      .putString("address", Authentication.authAddress)
      .putString("user_collection", "users")
      .putString("persistor_address", Database.dbAddress)
      .putNumber("session_timeout", 30 * 60 * 1000)
    //      .putBoolean("fake", true) // FIXME delete this when deploying on openshift!

    container.deployModule(mongoPersistorModName, configDb, { deploymentId: String =>
      container.deployModule(authManagerModName, configAuth, { deploymentId2: String =>
        container.deployVerticle("com.campudus.vertxmoduleregistry.ModuleRegistryServer", {
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