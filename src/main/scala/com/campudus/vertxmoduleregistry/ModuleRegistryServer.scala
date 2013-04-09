package com.campudus.vertxmoduleregistry

import org.vertx.java.platform.Verticle
import org.vertx.java.core.http.RouteMatcher
import org.vertx.java.core.http.HttpServerRequest
import org.vertx.java.core.Handler
import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.buffer.Buffer
import com.campudus.vertx.helpers.PostRequestReader
import com.campudus.vertxmoduleregistry.database.Database._
import com.campudus.vertxmoduleregistry.security.Authentication._
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import scala.util.Success
import scala.util.Failure
import scala.concurrent.ExecutionContext.Implicits.global
import java.net.URLDecoder
import org.vertx.java.core.AsyncResult
import org.vertx.java.core.file.FileProps
import org.vertx.java.core.Vertx
import scala.concurrent.Future
import scala.concurrent.Promise

class ModuleRegistryServer extends Verticle with VertxScalaHelpers {

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
    authorise(vertx, sessionID).onComplete {
      case Success(username) => p.success(true)
      case Failure(error) => p.success(false)
    }
    p.future
  }

  private def respondFailed(message: String)(implicit request: HttpServerRequest) =
    request.response.end(json.putString("status", "failed").putString("message", message).encode)

  private def respondError(messages: List[String])(implicit request: HttpServerRequest) = {
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

        latestApprovedModules(vertx, limit).onComplete {
          case Success(modules) =>
            /*
             {modules: [{...},{...}]}
             */
            val modulesArray = new JsonArray()
            modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
            req.response.end(json.putArray("modules", modulesArray).encode)
          case Failure(error) =>
            respondFailed(error.getMessage())
        }
    })

    rm.get("/unapproved", {
      implicit req: HttpServerRequest =>
        req.params().get("sessionID") match {
          case s: String =>
            isAuthorised(vertx, s).onSuccess {
              case true => unapproved(vertx).onComplete {
                case Success(modules) =>
                  val modulesArray = new JsonArray()
                  modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
                  req.response.end(json.putArray("modules", modulesArray).encode)
                case Failure(error) =>
                  respondFailed(error.getMessage())
              }
              case false => respondDenied
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
            login(vertx, username, password).onComplete {
              case Success(sessionID) =>
                req.response.end(json.putString("status", "ok").putString("sessionID", sessionID).encode)
              case Failure(error) =>
                respondDenied
            }
          } else {
            respondError(errors)
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
            authorise(vertx, sessionID).onComplete {
              case Success(username) =>
                approve(vertx, id).onComplete {
                  case Success(json) =>
                    req.response.end(json.encode())
                  case Failure(error) =>
                    respondFailed(error.getMessage())
                }
              case Failure(error) =>
                respondDenied
            }

          } else {
            respondError(errors)
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
            searchModules(vertx, query).onComplete {
              case Success(modules) =>
                val modulesArray = new JsonArray()
                modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
                req.response.end(json.putArray("modules", modulesArray).encode)
              case Failure(error) =>
                respondFailed(error.getMessage())
            }
          } else {
            req.response.end
          }
        })
    })

    rm.post("/register", {
      implicit req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)

          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          val downloadUrl = getRequiredParam("downloadUrl", "Download URL missing")
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
            registerModule(vertx, module).onComplete {
              case Success(json) =>
                req.response.end("Registered: " + module + " with id " + json.getString("_id"))
              case Failure(error) =>
                respondFailed(error.getMessage())
            }
          } else {
            respondError(errors)
          }

        })
    })

    rm.post("/logout", {
      implicit req: HttpServerRequest =>
        req.dataHandler({ buf: Buffer =>
          implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
          implicit val errorBuffer = collection.mutable.ListBuffer[String]()

          val sessionID = getRequiredParam("sessionID", "Session ID required")

          val errors = errorBuffer.result
          if (errors.isEmpty) {
            logout(vertx, sessionID).onComplete {
              case Success(oldSessionID) =>
                req.response.end(json.putString("status", "ok").putString("sessionID", oldSessionID).encode)
              case Failure(error) =>
                respondFailed(error.getMessage())
            }
          } else {
            respondError(errors)
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

    val host = container.getConfig().getString("host")
    val port = container.getConfig().getInteger("port")

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
    request.response.statusCode = statusCode
    request.response.sendFile(file)
  }

  private def deliverValidUrl(file: String)(implicit request: HttpServerRequest) {
    if (file.contains("..")) {
      deliver(403, "errors/403.html")
    } else {
      deliver(file)
    }
  }
}
