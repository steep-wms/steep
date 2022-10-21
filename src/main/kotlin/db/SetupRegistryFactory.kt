package db

import ConfigConstants.CLOUD_SETUPS_FILE
import ConfigConstants.CLOUD_SETUPS_FILE_DEPRECATED
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Creates [SetupRegistry] objects
 * @author Michel Kraemer
 */
object SetupRegistryFactory {
  private val log = LoggerFactory.getLogger(SetupRegistryFactory::class.java)

  /**
   * Create a new [SetupRegistry]
   */
  fun create(vertx: Vertx, config: JsonObject = vertx.orCreateContext.config()): SetupRegistry {
    val deprecatedSetupsFile = config.getString(CLOUD_SETUPS_FILE_DEPRECATED)
    if (deprecatedSetupsFile != null) {
      log.warn("Configuration item `$CLOUD_SETUPS_FILE_DEPRECATED' is " +
          "deprecated and will be removed in Steep 7. Use " +
          "`$CLOUD_SETUPS_FILE' instead.")
    }
    val setupsFile = config.getString(CLOUD_SETUPS_FILE) ?: deprecatedSetupsFile ?:
        throw IllegalStateException("Missing configuration item `$CLOUD_SETUPS_FILE'")
    return SetupRegistry(setupsFile, vertx)
  }
}
