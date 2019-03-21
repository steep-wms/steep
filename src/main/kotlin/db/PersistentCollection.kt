package db

/**
 * An interface for persistent collections
 * @author Michel Kraemer
 */
interface PersistentCollection {
  /**
   * Load the collection's contents
   */
  suspend fun load()

  /**
   * Persist the collection's contents
   */
  suspend fun persist()
}
