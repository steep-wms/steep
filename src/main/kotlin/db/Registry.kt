package db

/**
 * A registry that saves objects (in memory or in a database)
 * @author Michel Kraemer
 */
interface Registry {
  /**
   * Close the registry and release all resources
   */
  suspend fun close()
}
