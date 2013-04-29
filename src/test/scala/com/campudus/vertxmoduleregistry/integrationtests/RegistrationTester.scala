package com.campudus.vertxmoduleregistry.integrationtests

import org.junit.Test
import org.vertx.java.core.Handler
import org.vertx.testtools.VertxAssert._
import org.vertx.testtools.TestVerticle
import org.vertx.java.core.AsyncResult

class RegistrationTester extends TestVerticle {

  @Test
  def testDeployMod() {
    container.deployModule(System.getProperty("vertx.modulename"), new Handler[AsyncResult[String]]() {
      override def handle(deploymentID: AsyncResult[String]) {
        assertNotNull("deploymentID should not be null", deploymentID)
        testComplete()
      }
    })
  }

}