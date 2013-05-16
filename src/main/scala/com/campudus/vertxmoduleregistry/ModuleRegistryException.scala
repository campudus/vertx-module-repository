package com.campudus.vertxmoduleregistry

class ModuleRegistryException(message: String = "Unknown error", cause: Throwable = null) extends RuntimeException(message, cause)