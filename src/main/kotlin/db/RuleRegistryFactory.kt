package db

import io.vertx.core.Vertx
import java.lang.IllegalStateException

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
    val path = vertx.orCreateContext.config().getString(
        ConfigConstants.RULE_FILE) ?: throw IllegalStateException(
        "Missing configuration item `" + ConfigConstants.RULE_FILE + "'")
    return FileRuleRegistry(path)
  }
}
