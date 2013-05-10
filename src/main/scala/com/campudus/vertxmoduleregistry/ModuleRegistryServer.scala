package com.campudus.vertxmoduleregistry

import java.io.File
import java.net.{ URI, URLDecoder }
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import org.vertx.java.core.{ AsyncResult, Vertx }
import org.vertx.java.core.buffer.Buffer
import org.vertx.java.core.file.FileProps
import org.vertx.java.core.http.{ HttpServerRequest, RouteMatcher }
import org.vertx.java.core.json.{ JsonArray, JsonObject }
import com.campudus.vertx.Verticle
import com.campudus.vertx.helpers.{ PostRequestReader, VertxFutureHelpers, VertxScalaHelpers }
import com.campudus.vertxmoduleregistry.database.Database.{ Module, approve, latestApprovedModules, registerModule, searchModules, unapproved }
import com.campudus.vertxmoduleregistry.security.Authentication.{ authorise, login, logout }
import scala.util.Failure

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
    request.response().setStatusCode(400)
    request.response.putHeader("Set-Cookie", "lasterror=" + message + ";")
    request.response.sendFile(getWebPath + FILE_SEP + "errors" + FILE_SEP + "400.html")
  }

  private def respondErrors(messages: List[String])(implicit request: HttpServerRequest) = {
    val errorsAsJsonArr = new JsonArray
    messages.foreach(m => errorsAsJsonArr.addString(m))
    request.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
  }

  private def respondDenied(implicit request: HttpServerRequest) =
    request.response.end(json.putString("status", "denied").encode())

  override def start() {
    println("starting module registry")
    val rm = new RouteMatcher

    rm.get("/", { implicit req: HttpServerRequest =>
      deliver(getWebPath() + "/index.html")
    })
    rm.get("/register", { implicit req: HttpServerRequest =>
      deliver(getWebPath() + "/register.html")
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

    rm.get("/unapproved", {
      implicit req: HttpServerRequest =>
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

    rm.post("/login", {
      implicit req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
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

    rm.post("/approve", {
      implicit req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          val sessionID = getRequiredParam("sessionID", "Session ID required")
          val id = getRequiredParam("_id", "Module ID required")

          val errors = errorBuffer.result
          if (errors.isEmpty) {
            def callApprove() = {
              approve(vertx, id) onComplete {
                case Success(json) =>
                  req.response.end(json.encode())
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

    rm.post("/search", {
      implicit req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          val query = getRequiredParam("query", "Cannot search with empty keywords")

          val errors = errorBuffer.result
          if (errors.isEmpty) {
            searchModules(vertx, query) onComplete {
              case Success(modules) =>
                val modulesArray = new JsonArray()
                modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
                req.response.end(json.putArray("modules", modulesArray).encode)
              case Failure(error) => respondFailed(error.getMessage())
            }
          } else {
            req.response.end
          }
        })
    })

    rm.post("/register", {
      implicit req: HttpServerRequest =>
        val buf = new Buffer(0)
        println("post to /register")

        req.dataHandler({ buffer: Buffer =>
          println("data into buffer...")
          buf.appendBuffer(buffer)
        })
        req.endHandler({ () =>
          println("end handler of buffer!")

          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          try {
            val downloadUrl = new URI(getRequiredParam("downloadUrl", "Download URL missing"))

            (for {
              module <- downloadExtractAndRegister(downloadUrl)
              json <- registerModule(vertx, module)
            } yield {
              (module, json)
            }) onComplete {
              case Success((module, json)) =>
                req.response.setChunked(true)
                req.response.write("Registered: " + module + " with id " + json.getString("_id"))
                req.response.end("<a href=\"/\">Back to module registry</a>")
              case Failure(error) =>
                respondFailed(error.getMessage())
            }
          } catch {
            case e: Exception =>
              req.response().end("Download URL could not be parsed: " + e.getMessage())
              errorBuffer.append("Download URL could not be parsed")
          }
        })

      /*
          val name = getRequiredParam("modname", "Missing name")
          val owner = getRequiredParam("modowner", "Missing owner")
          val version = getRequiredParam("version", "Missing version of module")
          val vertxVersion = getRequiredParam("vertxVersion", "Missing vertx version")
          val description = getRequiredParam("description", "Missing description")
          val projectUrl = getRequiredParam("projectUrl", "Missing project URL")
          val author = getRequiredParam("author", "Missing author")
          val email = getRequiredParam("email", "Missing contact email")
          val license = getRequiredParam("license", "Missing license")
          val keywords = paramMap.get("keywords") match {
            case None => List()
            case Some(words) => URLDecoder.decode(words, "utf-8").split("\\s*,\\s*").toList
          }

          val errors = errorBuffer.result
          if (errors.isEmpty) {
            val module = Module(downloadUrl, name, owner, version, vertxVersion, description, projectUrl, author, email, license, keywords, System.currentTimeMillis())

            registerModule(vertx, module) onComplete {
              case Success(json) =>
                req.response.setChunked(true)
                req.response.write("Registered: " + module + " with id " + json.getString("_id"))
                req.response.end("<a href=\"/\">Back to module registry</a>")
              case Failure(error) => respondFailed(error.getMessage())
            }
          } else {
            respondErrors(errors)
          }
        }) */
    })

    rm.post("/logout", {
      implicit req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
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
      val errorDir = getWebPath() + "/errors"

      if (param.contains("..")) {
        deliver(403, errorDir + "403.html")
      } else {
        val path = getWebPath() + param

        vertx.fileSystem().props(path, {
          fprops: AsyncResult[FileProps] =>
            if (fprops.succeeded()) {
              if (fprops.result.isDirectory) {
                val indexFile = path + "/index.html"
                vertx.fileSystem().exists(indexFile, {
                  exists: AsyncResult[java.lang.Boolean] =>
                    if (exists.succeeded() && exists.result) {
                      logger.error("sending " + indexFile)
                      deliver(indexFile)
                    } else {
                      logger.error("could not find " + indexFile)
                      deliver(404, errorDir + "/404.html")
                    }
                })
              } else {
                deliver(path)
              }
            } else {
              logger.error("could not find " + path)
              deliver(404, errorDir + "/404.html")
            }
        })
      }
    })

    val config = Option(container.config()).getOrElse(json)
    val host = config.getString("host", "localhost")
    val port = config.getNumber("port", 8080)

    println("host: " + host)
    println("port: " + port)
    println("webpath: " + getWebPath())

    vertx.createHttpServer().requestHandler(rm).listen(port.intValue(), host);
    println("started module registry server")
  }

  override def stop() {
    println("stopped module registry server")
  }

  private def getWebPath() = "web"

  private def trimSlashes(path: String) = {
    path.replace("^/+", "").replace("/+$", "")
  }

  private def deliver(file: String)(implicit request: HttpServerRequest): Unit = deliver(200, file)(request)
  private def deliver(statusCode: Int, file: String)(implicit request: HttpServerRequest) {
    println("Delivering: " + file + " with code " + statusCode)
    request.response.setStatusCode(statusCode).sendFile(file)
  }

  private def deliverValidUrl(file: String)(implicit request: HttpServerRequest) {
    if (file.contains("..")) {
      deliver(403, "errors/403.html")
    } else {
      deliver(file)
    }
  }

  private def downloadExtractAndRegister(uri: URI): Future[Module] = {
    val tempUUID = java.util.UUID.randomUUID()
    val tempFile = "module-" + tempUUID + ".tmp.zip"
    val absPath = new File(tempFile).getAbsolutePath()

    for {
      file <- open(absPath)
      downloadedFile <- downloadInto(uri, file)
      destDir <- extract(absPath)
      modFileName <- modFileNameFromExtractedModule(destDir)
      modJson <- open(modFileName + FILE_SEP + "mod.json")
      content <- readFileToString(modJson)
    } yield {
      println("got mod.json:\n" + content.toString())
      val json = new JsonObject(content.toString())
      println("in json:\n" + json.encode())
      Module.fromJson(json.putNumber("timeRegistered", System.currentTimeMillis())) match {
        case Some(module) => module
        case None => throw new RuntimeException("cannot read module information from mod.json - fields missing?")
      }
    }
  }
}
