package com.campudus.vertxmoduleregistry.security

class AuthenticationException(message: String = null, cause: Throwable = null) extends RuntimeException(message, cause)

object AuthenticationException {
  def apply(message: String = null, cause: Throwable = null) = new SecurityException(message, cause)
}