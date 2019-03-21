package db

import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.core.shareddata.LocalMap

/**
 * A mutable map that is able to persist its contents to a Vert.x local map.
 * @param name the map's name
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class InMemoryPersistentMap<V>(name: String, vertx: Vertx) : PersistentMap<V>() {
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

  override suspend fun load(cls: Class<V>): PersistentMap<V> {
    // transfer everything from the local map to this
    clear()
    for ((k, v) in localMap) {
      put(k, JsonUtils.mapper.readValue(v, cls))
    }
    return this
  }

  override suspend fun persist() {
    // transfer everything from this to the local map
    localMap.clear()
    for ((k, v) in this) {
      localMap[k] = JsonUtils.mapper.writeValueAsString(v)
    }
  }
}
