package db

/**
 * A mutable map that is able to persist its contents. Implementations are not
 * supposed to be thread-safe. They must only be used by one owner at the same
 * time. Also, there must only be one instance of a persistent map with a given
 * name at the same time.
 * @author Michel Kraemer
 */
abstract class PersistentMap<V> : MutableMap<String, V> by mutableMapOf<String, V>() {
  /**
   * Load the map contents
   */
  abstract suspend fun load(cls: Class<V>): PersistentMap<V>

  /**
   * Persist the contents of this map
   */
  abstract suspend fun persist()
}
