package com.campudus.vertxmoduleregistry

import java.net.URL
import java.net.URLDecoder
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.Vertx
import org.vertx.java.core.buffer.Buffer
import org.vertx.java.core.file.FileProps
import org.vertx.java.core.http.HttpServerRequest
import org.vertx.java.core.http.RouteMatcher
import org.vertx.java.core.json.JsonArray
import org.vertx.java.core.json.JsonObject
import org.vertx.java.platform.Verticle
import com.campudus.vertx.helpers.PostRequestReader
import com.campudus.vertx.helpers.VertxFutureHelpers
import com.campudus.vertx.helpers.VertxScalaHelpers
import com.campudus.vertxmoduleregistry.database.Database.approve
import com.campudus.vertxmoduleregistry.database.Database.latestApprovedModules
import com.campudus.vertxmoduleregistry.database.Database.searchModules
import com.campudus.vertxmoduleregistry.database.Database.unapproved
import com.campudus.vertxmoduleregistry.security.Authentication.authorise
import com.campudus.vertxmoduleregistry.security.Authentication.login
import com.campudus.vertxmoduleregistry.security.Authentication.logout
import com.campudus.vertxmoduleregistry.database.Database._

class ModuleRegistryServer extends Verticle with VertxScalaHelpers with VertxFutureHelpers {

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

  private def respondFailed(message: String)(implicit request: HttpServerRequest) =
    request.response.end(json.putString("status", "failed").putString("message", message).encode)

  private def respondErrors(messages: List[String])(implicit request: HttpServerRequest) = {
    val errorsAsJsonArr = new JsonArray
    messages.foreach(m => errorsAsJsonArr.addString(m))
    request.response.end(json.putString("status", "error").putArray("messages", errorsAsJsonArr).encode)
  }

  private def respondDenied(implicit request: HttpServerRequest) =
    request.response.end(json.putString("status", "denied").encode())

  override def start() {
    val rm = new RouteMatcher

    rm.get("/", { req: HttpServerRequest =>
      req.response.sendFile(getWebPath() + "/index.html")
    })
    rm.get("/register", { req: HttpServerRequest =>
      req.response.sendFile(getWebPath() + "/register.html")
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
        req.dataHandler({ buffer: Buffer => buf.appendBuffer(buffer) })
        req.endHandler({ () =>
          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          try {
            val downloadUrl = new URL(getRequiredParam("downloadUrl", "Download URL missing"))
            for {
              module <- downloadExtractAndRegister(downloadUrl)
            } {
              registerModule(vertx, module) onComplete {
                case Success(json) =>
                  req.response.setChunked(true)
                  req.response.write("Registered: " + module + " with id " + json.getString("_id"))
                  req.response.end("<a href=\"/\">Back to module registry</a>")
                case Failure(error) => respondFailed(error.getMessage())
              }
            }
          } catch {
            case e: Exception => errorBuffer.append("Download URL could not be parsed")
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
                      deliver(indexFile)
                    } else {
                      deliver(404, errorDir + "/404.html")
                    }
                })
              } else {
                deliver(path)
              }
            } else {
              deliver(404, errorDir + "/404.html")
            }
        })
      }
    })

    val host = container.config().getString("host")
    val port = container.config().getInteger("port")

    println("host: " + host)
    println("port: " + port)
    println("webpath: " + getWebPath())

    vertx.createHttpServer().requestHandler(rm).listen(port, host);
    println("started server")
  }

  override def stop() {
    println("stopped server")
  }

  private def getWebPath() = System.getProperty("user.dir") + "/web"

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

  private def downloadExtractAndRegister(url: URL): Future[Module] = {
    val tempUUID = java.util.UUID.randomUUID()
    val tempFile = "module-" + tempUUID + ".tmp.zip"
    val dirName = "module-" + tempUUID + ".tmp"
    for {
      file <- open(tempFile)
      zip <- download(url, file)
      tempDir <- createDir(dirName)
      _ <- extract(zip, dirName)
      modJson <- open(dirName + "/mod.json") if (tempDir)
      content <- fileToString(modJson)
    } yield {
      val json = new JsonObject(content)
      Module.fromJson(json.putNumber("timeRegistered", System.currentTimeMillis())).get
    }
  }
}
