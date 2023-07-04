package db

import ConfigConstants.MACROS
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Creates [MacroRegistry] objects
 * @author Michel Kraemer
 */
object MacroRegistryFactory {
  /**
   * Create a new [MacroRegistry]
   */
  fun create(vertx: Vertx, config: JsonObject = vertx.orCreateContext.config()): MacroRegistry {
    val pathList = when (val paths = config.getValue(MACROS)) {
      null -> emptyList()
      is JsonArray -> paths.list.map { it as String }
      is String -> listOf(paths)
      else -> throw IllegalStateException("Configuration item " +
          "`$MACROS' must either be a string or an array")
    }
    return FileMacroRegistry(pathList, vertx)
  }
}
