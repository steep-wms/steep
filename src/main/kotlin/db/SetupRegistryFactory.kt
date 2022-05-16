package db

import ConfigConstants.CLOUD_SETUPS_FILE
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

/**
 * Creates [SetupRegistry] objects
 * @author Michel Kraemer
 */
object SetupRegistryFactory {
  /**
   * Create a new [SetupRegistry]
   */
  fun create(vertx: Vertx, config: JsonObject = vertx.orCreateContext.config()): SetupRegistry {
    val setupsFile = config.getString(CLOUD_SETUPS_FILE) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SETUPS_FILE'")
    return SetupRegistry(setupsFile, vertx)
  }
}
