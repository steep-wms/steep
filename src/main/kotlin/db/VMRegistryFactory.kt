package db

import db.RegistryFactoryConstants.DRIVER_INMEMORY
import db.RegistryFactoryConstants.DRIVER_MONGODB
import db.RegistryFactoryConstants.DRIVER_POSTGRESQL
import io.vertx.core.Vertx
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
   * @return the [VMRegistry]
   */
  fun create(vertx: Vertx): VMRegistry {
    val config = vertx.orCreateContext.config()
    val driver = config.getString(ConfigConstants.DB_DRIVER, DRIVER_INMEMORY)
    val url = config.getString(ConfigConstants.DB_URL)
    val username = config.getString(ConfigConstants.DB_USERNAME)
    val password = config.getString(ConfigConstants.DB_PASSWORD)
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
