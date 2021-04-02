package db

import db.RegistryFactoryConstants.DRIVER_INMEMORY
import db.RegistryFactoryConstants.DRIVER_MONGODB
import db.RegistryFactoryConstants.DRIVER_POSTGRESQL
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException

/**
 * Creates [VMRegistry] objects
 * @author Michel Kraemer
 */
object VMRegistryFactory {
  private val log = LoggerFactory.getLogger(VMRegistryFactory::class.java)

  /**
   * Create a new [VMRegistry]
   * @param vertx the current Vert.x instance
   * @param config optional configuration object that overrides the current
   * Vert.x context's configuration
   * @return the [VMRegistry]
   */
  fun create(vertx: Vertx, config: JsonObject? = null): VMRegistry {
    val conf = config ?: vertx.orCreateContext.config()
    val driver = conf.getString(ConfigConstants.DB_DRIVER, DRIVER_INMEMORY)
    val url = conf.getString(ConfigConstants.DB_URL)
    val username = conf.getString(ConfigConstants.DB_USERNAME)
    val password = conf.getString(ConfigConstants.DB_PASSWORD)
    log.info("Using database driver: $driver")
    val result = when (driver) {
      DRIVER_INMEMORY -> InMemoryVMRegistry(vertx)
      DRIVER_POSTGRESQL -> PostgreSQLVMRegistry(vertx, url, username, password)
      DRIVER_MONGODB -> MongoDBVMRegistry(vertx, url)
      else -> throw IllegalStateException("Unknown database driver `$driver'")
    }
    return NotifyingVMRegistry(result, vertx)
  }
}
