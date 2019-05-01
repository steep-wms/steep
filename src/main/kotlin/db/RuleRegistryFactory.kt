package db

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray

/**
 * Creates [RuleRegistry] objects
 * @author Michel Kraemer
 */
object RuleRegistryFactory {
  /**
   * Create a new [RuleRegistry]
   * @param vertx the current Vert.x instance
   * @return the [RuleRegistry]
   */
  fun create(vertx: Vertx): RuleRegistry {
    val paths = vertx.orCreateContext.config().getValue(
        ConfigConstants.RULES) ?: throw IllegalStateException(
        "Missing configuration item `${ConfigConstants.RULES}'")
    val pathList = when (paths) {
      is JsonArray -> paths.list.map { it as String }
      is String -> listOf(paths)
      else -> throw IllegalStateException("Configuration item " +
          "`${ConfigConstants.RULES}' must either be a string or an array")
    }
    return FileRuleRegistry(pathList, vertx)
  }
}
