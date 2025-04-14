package db

import db.RegistryFactoryConstants.DRIVER_INMEMORY
import db.RegistryFactoryConstants.DRIVER_MONGODB
import db.RegistryFactoryConstants.DRIVER_POSTGRESQL
import io.vertx.core.Vertx
import org.slf4j.LoggerFactory

/**
 * Creates [SubmissionRegistry] objects
 * @author Michel Kraemer
 */
object SubmissionRegistryFactory {
  private val log = LoggerFactory.getLogger(SubmissionRegistryFactory::class.java)

  /**
   * Create a new [SubmissionRegistry]
   * @param vertx the current Vert.x instance
   * @return the [SubmissionRegistry]
   */
  fun create(vertx: Vertx): SubmissionRegistry {
    val config = vertx.orCreateContext.config()
    val driver = config.getString(ConfigConstants.DB_DRIVER, DRIVER_INMEMORY)
    val url = config.getString(ConfigConstants.DB_URL)
    val username = config.getString(ConfigConstants.DB_USERNAME)
    val password = config.getString(ConfigConstants.DB_PASSWORD)
    log.info("Using database driver: $driver")
    val result = when (driver) {
      DRIVER_INMEMORY -> InMemorySubmissionRegistry(vertx)
      DRIVER_POSTGRESQL -> PostgreSQLSubmissionRegistry(vertx, url, username, password)
      DRIVER_MONGODB -> MongoDBSubmissionRegistry(vertx, url)
      else -> throw IllegalStateException("Unknown database driver `$driver'")
    }
    return NotifyingSubmissionRegistry(result, vertx)
  }
}
