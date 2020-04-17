package db

/**
 * Constants used in registry factories
 * @author Michel Kraemer
 */
object RegistryFactoryConstants {
  /**
   * The in-memory database driver
   */
  const val DRIVER_INMEMORY = "inmemory"

  /**
   * The PostgreSQL database driver
   */
  const val DRIVER_POSTGRESQL = "postgresql"

  /**
   * The MongoDB database driver
   */
  const val DRIVER_MONGODB = "mongodb"
}
