package com.campudus.vertxmoduleregistry.database

class DatabaseException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause)

object DatabaseException {
  def apply(message: String = null, cause: Throwable = null) = new DatabaseException(message, cause)
}