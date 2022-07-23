package helper.hazelcast

import io.vertx.core.Vertx
import io.vertx.core.shareddata.LocalMap

/**
 * In-memory implementation of [ClusterMap] that can be used for testing purposes
 * @author Michel Kraemer
 */
class DummyClusterMap<K : Any, V : Any>(name: String, vertx: Vertx) : ClusterMap<K, V> {
  private val map: LocalMap<K, V> = vertx.sharedData().getLocalMap(name)
  private val entryRemovedListeners = mutableListOf<(K) -> Unit>()

  override suspend fun size(): Int = map.size
  override suspend fun keys(): Set<K> = map.keys

  override fun addPartitionLostListener(listener: () -> Unit) {
    // nothing to do here
  }

  override fun addEntryRemovedListener(listener: (K) -> Unit) {
    entryRemovedListeners.add(listener)
  }

  override suspend fun delete(key: K) {
    map.remove(key)
    for (l in entryRemovedListeners) {
      l(key)
    }
  }

  override suspend fun putIfAbsent(key: K, value: V): V? = map.putIfAbsent(key, value)

  override suspend fun put(key: K, value: V): V? = map.put(key, value)
}
