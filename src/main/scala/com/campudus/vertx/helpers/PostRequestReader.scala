package com.campudus.vertx.helpers

import scala.collection.immutable.Map
import org.vertx.java.core.buffer.Buffer

/**
 * Helper object to read data from a HTTP-POST request.
 */
object PostRequestReader {

  /**
   * Writes a POST data string into a map.
   * @param data The POST data.
   * @return A Map containing the POST data as key/value pairs.
   */
  def dataToMap(data: String): Map[String, String] = {
    val Entry = """(.*?)=(.*?)(?:&|$)""".r

    (for (entry <- (Entry findAllIn data)) yield entry match {
      case Entry(key, value) => (key, value)
    }).toSeq.toMap
  }

}