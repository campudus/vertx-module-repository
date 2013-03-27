package com.campudus.vertxmoduleregistry

import org.vertx.java.platform.Verticle
import org.vertx.java.core.http.RouteMatcher
import org.vertx.java.core.http.HttpServerRequest
import org.vertx.java.core.Handler
import com.campudus.vertx.helpers.VertxScalaHelpers
import org.vertx.java.core.buffer.Buffer
import com.campudus.vertx.helpers.PostRequestReader
import com.campudus.vertxmoduleregistry.database.Database._
import org.vertx.java.core.json.JsonObject
import org.vertx.java.core.json.JsonArray
import scala.util.Success
import scala.util.Failure
import scala.concurrent.ExecutionContext.Implicits.global
import java.net.URLDecoder

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

  override def start() {
    val rm = new RouteMatcher

    rm.get("/", { req: HttpServerRequest =>
      req.response.sendFile(System.getProperty("user.dir") + "/web/index.html")
    })

    rm.get("/last-approved-modules", {
      req: HttpServerRequest =>
        //    	  req.params().get("limit") match {
        //        case str: String =>
        lastApprovedModules(vertx, 5).onComplete {
          case Success(modules) =>
            /*
             {modules: [{...},{...}]}
             */
            val modulesArray = new JsonArray()
            modules.map(_.toJson).foreach(m => modulesArray.addObject(m))
            req.response.end(json.putArray("modules", modulesArray).encode)
          case Failure(error) =>
            req.response.end("Error occuring while listing last approved modules: " + error.getMessage())
        }
      //        case _ => 
      //      }
    })

    rm.post("/search", { req: HttpServerRequest =>
      req.dataHandler({ buf: Buffer =>
        implicit val paramMap = PostRequestReader.dataToMap(buf.toString)
        implicit val errorBuffer = collection.mutable.ListBuffer[String]()

        val query = getRequiredParam("query", "Cannot search with empty keywords")

        val errors = errorBuffer.result
        if (errors.isEmpty) {
          searchModules(vertx, query).onComplete {
            case Success(modules) =>
              req.response.setChunked(true)
              modules.map(_.toJson.encode()).foreach(m => req.response.write(m.toString()))
              req.response.end
            case Failure(error) =>
              req.response.end("Error occuring while searching for modules: " + error.getMessage())
          }
        }
        //        else {
        //          req.response.setChunked(true)
        //          req.response.putHeader("Content-type", "text/html")
        //
        //          req.response.write("<p>Errors occuring while searching for modules:</p>\n")
        //          req.response.write(errors.mkString("<ul>\n<li>", "</li>\n<li>", "</li>\n</ul>"))
        //          req.response.end("<p>Please try again.</p>")
        //        }
      })
    })

    rm.post("/register", { req: HttpServerRequest =>
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
              req.response.end("Error occured while registering module: " + error.getMessage())
          }
        } else {
          req.response.setChunked(true)
          req.response.putHeader("Content-type", "text/html")

          req.response.write("<p>Errors occured while registering module:</p>\n")
          req.response.write(errors.mkString("<ul>\n<li>", "</li>\n<li>", "</li>\n</ul>"))
          req.response.end("<p>Please re-submit.</p>")
        }

      })
    })

    vertx.createHttpServer().requestHandler(rm).listen(8080);
    println("started server")
  }

  override def stop() {
    println("stopped server")
  }
}