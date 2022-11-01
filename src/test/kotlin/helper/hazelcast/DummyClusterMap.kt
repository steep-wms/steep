package helper.hazelcast

import io.vertx.core.Vertx
import io.vertx.core.shareddata.LocalMap

/**
 * In-memory implementation of [ClusterMap] that can be used for testing purposes
 * @author Michel Kraemer
 */
class DummyClusterMap<K : Any, V : Any>(name: String, vertx: Vertx) : ClusterMap<K, V> {
  private val context = vertx.orCreateContext
  private val map: LocalMap<K, V> = vertx.sharedData().getLocalMap(name)
  private val entryAddedListeners = mutableListOf<Pair<(K, V?) -> Unit, Boolean>>()
  private val entryRemovedListeners = mutableListOf<(K) -> Unit>()

  override suspend fun size(): Int = map.size
  override suspend fun keys(): Set<K> = map.keys
  override suspend fun entries(): Set<Map.Entry<K, V>> = map.entries

  override fun addPartitionLostListener(listener: () -> Unit) {
    // nothing to do here
  }

  override fun addEntryAddedListener(includeValue: Boolean, listener: (K, V?) -> Unit) {
    entryAddedListeners.add(listener to includeValue)
  }

  override fun addEntryRemovedListener(listener: (K) -> Unit) {
    entryRemovedListeners.add(listener)
  }

  override fun addEntryMergedListener(includeValue: Boolean,
      listener: (K, V?) -> Unit) {
    // nothing to do here
  }

  override suspend fun delete(key: K) {
    map.remove(key)
    for (l in entryRemovedListeners) {
      context.runOnContext {
        l(key)
      }
    }
  }

  override suspend fun putIfAbsent(key: K, value: V): V? {
    val r = map.putIfAbsent(key, value)
    if (r == null) {
      for ((l, includeValue) in entryAddedListeners) {
        context.runOnContext {
          l(key, if (includeValue) value else null)
        }
      }
    }
    return r
  }

  override suspend fun get(key: K): V? = map[key]

  override suspend fun put(key: K, value: V): V? {
    val r = map.put(key, value)
    if (r == null) {
      for ((l, includeValue) in entryAddedListeners) {
        context.runOnContext {
          l(key, if (includeValue) value else null)
        }
      }
    }
    return r
  }

  override suspend fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V? {
    val r = map.computeIfAbsent(key, mappingFunction)
    if (r != null) {
      for ((l, includeValue) in entryAddedListeners) {
        context.runOnContext {
          l(key, if (includeValue) r else null)
        }
      }
    }
    return r
  }

  override suspend fun computeIfPresent(key: K, remappingFunction: (K, V) -> V): V? {
    return map.computeIfPresent(key, remappingFunction)
  }
}
