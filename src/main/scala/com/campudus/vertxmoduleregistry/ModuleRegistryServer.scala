package com.campudus.vertxmoduleregistry

import java.io.File
import java.net.{ URI, URLDecoder }
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import org.vertx.java.core.{ AsyncResult, Vertx }
import org.vertx.java.core.buffer.Buffer
import org.vertx.java.core.eventbus.Message
import org.vertx.java.core.file.FileProps
import org.vertx.java.core.http.{ HttpServerRequest, RouteMatcher }
import org.vertx.java.core.json.{ JsonArray, JsonObject }
import com.campudus.vertx.Verticle
import com.campudus.vertx.helpers.{ PostRequestReader, VertxFutureHelpers, VertxScalaHelpers }
import com.campudus.vertxmoduleregistry.database.Database.{ Module, approve, latestApprovedModules, listModules, registerModule, remove, searchModules, unapproved }
import com.campudus.vertxmoduleregistry.security.Authentication.{ authorise, login, logout }
import org.vertx.java.core.file.AsyncFile
import java.io.IOException
import java.nio.file.Files
import java.io.FileNotFoundException
import org.vertx.java.core.file.FileSystemException
import java.nio.file.NoSuchFileException

class ModuleRegistryServer extends Verticle with VertxScalaHelpers with VertxFutureHelpers {
  import com.campudus.vertx.DefaultVertxExecutionContext._

  val FILE_SEP = File.separator

  private def getRequiredParam(param: String, error: String)(implicit paramMap: Map[String, String], errors: collection.mutable.ListBuffer[String]) = {
    def addError() = {
      errors += error
      ""
    }

    paramMap.get(param) match {
      case None => addError
      case Some(str) if (str.matches("\\s*")) => addError
      case Some(str) => URLDecoder.decode(str, "utf-8")
    }
  }

  def isAuthorised(vertx: Vertx, sessionID: String): Future[Boolean] = {
    val p = Promise[Boolean]

    authorise(vertx, sessionID) onComplete {
      case Success(username) => p.success(true)
      case Failure(error) => p.success(false)
    }

    p.future
  }

  private def respondFailed(message: String)(implicit request: HttpServerRequest) = {
    request.response.end(s"""{"status":"error","message":"${message}"}""")
  }

  private def respondErrors(messages: List[String])(implicit request: HttpServerRequest) = {
    val errorsAsJsonArr = new JsonArray
    messages.foreach(m => errorsAsJsonArr.addString(m))
    request.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
  }

  private def respondDenied(implicit request: HttpServerRequest) =
    request.response.end(json.putString("status", "denied").encode())

  override def start() {
    logger.info("starting module registry")
    val rm = new RouteMatcher

    rm.get("/", { implicit req: HttpServerRequest =>
      deliver(webPath + FILE_SEP + "index.html")
    })

    rm.get("/latest-approved-modules", {
      implicit req: HttpServerRequest =>
        val limit = req.params().get("limit") match {
          case s: String => toInt(s).getOrElse(5)
          case _ => 5
        }

        latestApprovedModules(vertx, limit) onComplete {
          case Success(modules) => /*
             {modules: [{...},{...}]}
             */
            val modulesArray = new JsonArray()
            modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
            req.response.end(json.putArray("modules", modulesArray).encode)
          case Failure(error) => respondFailed(error.getMessage())
        }
    })

    rm.get("/list", {
      implicit req: HttpServerRequest =>
        val limit = Option(req.params().get("limit")) flatMap toInt
        val skip = Option(req.params().get("skip")) flatMap toInt

        listModules(vertx, limit, skip) onComplete {
          case Success(modules) =>
            println("got some modules: " + modules)
            val modulesArray = new JsonArray()
            modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
            req.response.end(json.putArray("modules", modulesArray).encode)
          case Failure(error) => respondFailed(error.getMessage())
        }
    })

    rm.get("/unapproved", { implicit req: HttpServerRequest =>
      req.params().get("sessionID") match {
        case s: String => {
          def callUnapproved() = {
            unapproved(vertx) onComplete {
              case Success(modules) =>
                val modulesArray = new JsonArray()
                modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
                req.response.end(json.putArray("modules", modulesArray).encode)
              case Failure(error) => respondFailed(error.getMessage())
            }
          }

          isAuthorised(vertx, s) map {
            case true => callUnapproved
            case false => respondDenied
          }
        }
        case _ => respondDenied
      }

    })

    rm.post("/login", { implicit req: HttpServerRequest =>
      req.bodyHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val username = "approver"
        val password = getRequiredParam("password", "Missing password")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          login(vertx, username, password) onComplete {
            case Success(sessionID) =>
              req.response.end(json.putString("status", "ok").putString("sessionID", sessionID).encode)
            case Failure(_) => respondDenied
          }
        } else {
          respondErrors(errors)
        }
      })
    })

    rm.post("/approve", { implicit req: HttpServerRequest =>
      req.bodyHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val sessionID = getRequiredParam("sessionID", "Session ID required")
        val id = getRequiredParam("_id", "Module ID required")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          def callApprove() = {
            approve(vertx, id) onComplete {
              case Success(json) => req.response.end(json.encode())
              case Failure(error) => respondFailed(error.getMessage())
            }
          }

          isAuthorised(vertx, sessionID) map {
            case true => callApprove
            case false => respondDenied
          }
        } else {
          respondErrors(errors)
        }
      })
    })

    rm.post("/remove", { implicit req: HttpServerRequest =>
      req.bodyHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val sessionID = getRequiredParam("sessionID", "Session ID required")
        val name = getRequiredParam("name", "Module ID required")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          def callRemove() = {
            remove(vertx, name) onComplete {
              case Success(json) => req.response.end(json.encode())
              case Failure(error) => respondFailed(error.getMessage())
            }
          }

          isAuthorised(vertx, sessionID) map {
            case true => callRemove
            case false => respondDenied
          }
        } else {
          respondErrors(errors)
        }
      })
    })

    rm.post("/search", { implicit req: HttpServerRequest =>
      req.bodyHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val query = getRequiredParam("query", "Cannot search with empty keywords")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          searchModules(vertx, query) onComplete {
            case Success(modules) =>
              val modulesArray = new JsonArray()
              modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
              req.response.end(json.putString("status", "ok").putArray("modules", modulesArray).encode)
            case Failure(error) => respondFailed(error.getMessage())
          }
        } else {
          respondErrors(errors)
        }
      })
    })

    rm.post("/register", { implicit req: HttpServerRequest =>
      req.bodyHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val url = getRequiredParam("downloadUrl", "Download URL missing")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          try {
            val downloadUrl = new URI(url)

            (for {
              module <- downloadExtractAndRead(downloadUrl)
              json <- registerModule(vertx, module)
              sent <- sendMailToModerators(module)
            } yield {
              (module, json, sent)
            }) onComplete {
              case Success((module, json, sent)) =>
                req.response.end(s"""{"status":"ok","mailSent":${sent},"data":${module.toSensibleJson.encode()}}""")
              case Failure(error) =>
                logger.info("failed -> error response " + error.getMessage())
                respondFailed(error.getMessage())
            }
          } catch {
            case e: Exception =>
              respondFailed("Download URL could not be parsed" + e.getMessage())
          }
        } else {
          respondErrors(errors)
        }
      })
    })

    rm.post("/logout", { implicit req: HttpServerRequest =>
      req.bodyHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val sessionID = getRequiredParam("sessionID", "Session ID required")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          logout(vertx, sessionID) onComplete {
            case Success(oldSessionID) =>
              req.response.end(json.putString("status", "ok").putString("sessionID", oldSessionID).encode)
            case Failure(error) => respondFailed(error.getMessage())
          }
        } else {
          respondErrors(errors)
        }
      })
    })

    rm.getWithRegEx("^(.*)$", { implicit req: HttpServerRequest =>
      val param0 = trimSlashes(req.params.get("param0"))
      val param = if (param0 == "") {
        "index.html"
      } else {
        param0
      }
      val errorDir = webPath + FILE_SEP + "errors"

      if (param.contains("..")) {
        deliver(403, errorDir + "403.html")
      } else {
        val path = webPath + param

        vertx.fileSystem().props(path, {
          fprops: AsyncResult[FileProps] =>
            if (fprops.succeeded()) {
              if (fprops.result.isDirectory) {
                val indexFile = path + FILE_SEP + "index.html"
                vertx.fileSystem().exists(indexFile, {
                  exists: AsyncResult[java.lang.Boolean] =>
                    if (exists.succeeded() && exists.result) {
                      logger.error("sending " + indexFile)
                      deliver(indexFile)
                    } else {
                      logger.error("could not find " + indexFile)
                      deliver(404, errorDir + FILE_SEP + "404.html")
                    }
                })
              } else {
                deliver(path)
              }
            } else {
              logger.error("could not find " + path)
              deliver(404, errorDir + FILE_SEP + "404.html")
            }
        })
      }
    })

    val config = Option(container.config()).getOrElse(json)
    val host = config.getString("host", "localhost")
    val port = config.getNumber("port", 8080)
    val ssl = config.getString("keystore-path") != null && config.getString("keystore-pass") != null

    logger.info("host: " + host)
    logger.info("port: " + port)
    logger.info("this path: " + new File(".").getAbsolutePath())
    logger.info("webpath: " + webPath)

    if (ssl) {
      vertx.createHttpServer()
        .requestHandler(rm)
        .setSSL(true)
        .setKeyStorePath(config.getString("keystore-path"))
        .setKeyStorePassword(config.getString("keystore-pass"))
        .listen(port.intValue(), host)
    } else {
      vertx.createHttpServer()
        .requestHandler(rm)
        .listen(port.intValue(), host)
    }

    logger.info("started module registry server")
  }

  override def stop() {
    logger.info("stopped module registry server")
  }

  lazy val webPath = (new File(".")).getAbsolutePath() + FILE_SEP + "web"

  private def trimSlashes(path: String) = {
    path.replace("^/+", "").replace("/+$", "")
  }

  private def deliver(file: String)(implicit request: HttpServerRequest): Unit = deliver(200, file)(request)
  private def deliver(statusCode: Int, file: String)(implicit request: HttpServerRequest) {
    logger.info("Delivering: " + file + " with code " + statusCode)
    request.response.setStatusCode(statusCode).sendFile(file)
  }

  private def deliverValidUrl(file: String)(implicit request: HttpServerRequest) {
    if (file.contains("..")) {
      deliver(403, "errors" + FILE_SEP + "403.html")
    } else {
      deliver(file)
    }
  }

  private def downloadExtractAndRead(uri: URI): Future[Module] = {
    val tempUUID = java.util.UUID.randomUUID()
    val tempFile = "module-" + tempUUID + ".tmp.zip"
    val absPath = File.createTempFile("module-", tempUUID + ".tmp.zip").getAbsolutePath()
    val tempDir = Files.createTempDirectory("vertx-" + tempUUID.toString())
    val destDir = tempDir.toAbsolutePath().toString()

    val futureModule = for {
      file <- open(absPath)
      downloadedFile <- downloadInto(uri, file)
      _ <- extract(absPath, destDir)
      modFileName <- modFileNameFromExtractedModule(destDir)
      modJson <- open(modFileName)
      content <- readFileToString(modJson)
    } yield {
      logger.info("got mod.json:\n" + content.toString())
      val json = new JsonObject(content.toString()).putString("downloadUrl", uri.toString())
      logger.info("in json:\n" + json.encode())
      Module.fromModJson(json.putNumber("timeRegistered", System.currentTimeMillis())) match {
        case Some(module) => module
        case None => throw new ModuleRegistryException("cannot read module information from mod.json - fields missing?")
      }
    }

    futureModule andThen {
      case _ =>
        cleanUpFile(absPath) onFailure logCleanupFail(absPath)
        cleanUpFile(destDir) onFailure logCleanupFail(destDir)
    }
  }

  private def logCleanupFail(file: String): PartialFunction[Throwable, Unit] = {
    case ex: FileSystemException =>
      ex.getCause() match {
        case ex: NoSuchFileException => // don't care
        case _ => logger.error("Could not clean up file: " + file, ex)
      }
    case ex: Throwable => logger.error("Could not clean up file: " + file, ex)
  }

  private def cleanUpFile(file: String) = {
    val p = Promise[Unit]
    vertx.fileSystem().delete(file, true, { res: AsyncResult[Void] =>
      if (res.succeeded()) {
        p.success()
      } else {
        p.failure(res.cause)
      }
    })
    p.future
  }

  private def sendMailToModerators(mod: Module): Future[Boolean] = {
    logger.info("in send mail")
    val mailerConf = container.config().getObject("mailer", json)
    logger.info("mailerConf: " + mailerConf)
    val email = Option(mailerConf.getString("infoMail"))
    logger.info("email: " + email)
    val moderators = Option(mailerConf.getArray("moderators"))
    logger.info("moderators: " + moderators)

    if (email.isDefined && moderators.isDefined) {
      val data = json
        .putString("from", email.get)
        .putArray("to", moderators.get)
        .putString("subject", "New module waiting for approval: " + mod.name)
        .putString("body", mod.toWaitForApprovalEmailString())

      val promise = Promise[Boolean]
      vertx.eventBus.send(ModuleRegistryStarter.mailerAddress, data, { msg: Message[JsonObject] =>
        logger.info("mailed something and received: " + msg.body.encode())
        if ("ok" == msg.body.getString("status")) {
          promise.success(true)
        } else {
          promise.success(false)
        }
      })
      promise.future
    } else {
      Future.successful(false)
    }
  }
}
