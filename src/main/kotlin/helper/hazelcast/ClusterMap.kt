package helper.hazelcast

import com.hazelcast.map.IMap
import globalHazelcastInstance
import io.vertx.core.Vertx

/**
 * A thin wrapper around Hazelcast's [IMap]
 * @author Michel Kraemer
 */
interface ClusterMap<K : Any, V : Any> {
  companion object {
    fun <K : Any, V : Any> create(name: String, vertx: Vertx): ClusterMap<K, V> {
      return ClusterMapImpl(globalHazelcastInstance.getMap(name), vertx)
    }
  }

  suspend fun size(): Int
  suspend fun get(key: K): V?
  suspend fun put(key: K, value: V): V?
  suspend fun putIfAbsent(key: K, value: V): V?
  suspend fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V?
  suspend fun computeIfPresent(key: K, remappingFunction: (K, V) -> V): V?
  suspend fun delete(key: K)
  suspend fun entries(): Set<Map.Entry<K, V>>
  suspend fun keys(): Set<K>
  fun addEntryAddedListener(includeValue: Boolean, listener: (K, V?) -> Unit)
  fun addEntryRemovedListener(listener: (K) -> Unit)
  fun addEntryMergedListener(includeValue: Boolean, listener: (K, V?) -> Unit)
  fun addPartitionLostListener(listener: () -> Unit)
}
