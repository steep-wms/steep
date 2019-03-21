package db

/**
 * A mutable map that is able to persist its contents. Implementations are not
 * supposed to be thread-safe. They must only be used by one owner at the same
 * time. Also, there must only be one instance of a persistent map with a given
 * name at the same time. Persistent maps should be cleared when they are not
 * needed anymore to save memory.
 * @author Michel Kraemer
 */
interface PersistentMap<K, V> : MutableMap<K, V> {
  /**
   * Load the map contents
   */
  suspend fun load(): PersistentMap<K, V>

  /**
   * Persist the contents of this map
   */
  suspend fun persist()
}

/**
 * Abstract implementation of [PersistentMap]
 */
abstract class PersistentMapAdapter<K, V> :
    MutableMap<K, V> by mutableMapOf<K, V>(), PersistentMap<K, V>
