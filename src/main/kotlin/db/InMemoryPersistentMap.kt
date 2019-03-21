package db

import io.vertx.core.Vertx
import io.vertx.core.shareddata.LocalMap

/**
 * A mutable map that is able to persist its contents to a Vert.x local map.
 * @param name the map's name
 * @param keySerialize a function that serializes keys
 * @param keyDeserialize a function that deserializes keys
 * @param valueSerialize a function that serializes values
 * @param valueDeserialize a function that deserializes values
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class InMemoryPersistentMap<K, V>(
    name: String,
    private val keySerialize: (K) -> String,
    private val keyDeserialize: (String) -> K,
    private val valueSerialize: (V) -> String,
    private val valueDeserialize: (String) -> V,
    vertx: Vertx
) : PersistentMapAdapter<K, V>() {
  companion object {
    /**
     * A prefix used to name the local map
     */
    const val PERSISTENTMAP_PREFIX = "InMemoryPersistentMap."
  }

  /**
   * The local map to persist to
   */
  private val localMap: LocalMap<String, String> = vertx.sharedData().getLocalMap(
      PERSISTENTMAP_PREFIX + name)

  override suspend fun load(): PersistentMap<K, V> {
    // transfer everything from the local map to this
    clear()
    for ((k, v) in localMap) {
      put(keyDeserialize(k), valueDeserialize(v))
    }
    return this
  }

  override suspend fun persist() {
    // transfer everything from this to the local map
    localMap.clear()
    for ((k, v) in this) {
      localMap[keySerialize(k)] = valueSerialize(v)
    }
  }
}
