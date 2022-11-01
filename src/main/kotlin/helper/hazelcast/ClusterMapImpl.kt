package helper.hazelcast

import com.hazelcast.core.EntryEvent
import com.hazelcast.map.IMap
import com.hazelcast.map.listener.EntryAddedListener
import com.hazelcast.map.listener.EntryMergedListener
import com.hazelcast.map.listener.EntryRemovedListener
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.await

/**
 * A thin wrapper around Hazelcast's [IMap]
 * @author Michel Kraemer
 */
class ClusterMapImpl<K : Any, V : Any>(private val map: IMap<K, V>,
    private val vertx: Vertx) : ClusterMap<K, V> {
  private val context = vertx.orCreateContext

  override suspend fun size(): Int {
    return vertx.executeBlocking({ p ->
      p.complete(map.size)
    }, false).await()
  }

  override suspend fun get(key: K): V? {
    return vertx.executeBlocking({ p ->
      p.complete(map[key])
    }, false).await()
  }

  override suspend fun put(key: K, value: V): V? {
    return vertx.executeBlocking({ p ->
      p.complete(map.put(key, value))
    }, false).await()
  }

  override suspend fun putIfAbsent(key: K, value: V): V? {
    return vertx.executeBlocking({ p ->
      p.complete(map.putIfAbsent(key, value))
    }, false).await()
  }

  override suspend fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V? {
    return vertx.executeBlocking({ p ->
      p.complete(map.computeIfAbsent(key, mappingFunction))
    }, false).await()
  }

  override suspend fun computeIfPresent(key: K, remappingFunction: (K, V) -> V): V? {
    return vertx.executeBlocking({ p ->
      p.complete(map.computeIfPresent(key, remappingFunction))
    }, false).await()
  }

  override suspend fun delete(key: K) {
    return vertx.executeBlocking({ p ->
      p.complete(map.delete(key))
    }, false).await()
  }

  override suspend fun entries(): Set<Map.Entry<K, V>> {
    return vertx.executeBlocking({ p ->
      p.complete(map.entries)
    }, false).await()
  }

  override suspend fun keys(): Set<K> {
    return vertx.executeBlocking({ p ->
      p.complete(map.keys)
    }, false).await()
  }

  override fun addEntryAddedListener(includeValue: Boolean, listener: (K, V?) -> Unit) {
    map.addEntryListener(object : EntryAddedListener<K, V> {
      override fun entryAdded(event: EntryEvent<K, V?>) {
        context.runOnContext {
          listener(event.key, event.value)
        }
      }
    }, includeValue)
  }

  override fun addEntryRemovedListener(listener: (K) -> Unit) {
    map.addEntryListener(object : EntryRemovedListener<K, V> {
      override fun entryRemoved(event: EntryEvent<K, V?>) {
        context.runOnContext {
          listener(event.key)
        }
      }
    }, false)
  }

  override fun addEntryMergedListener(includeValue: Boolean, listener: (K, V?) -> Unit) {
    map.addEntryListener(object : EntryMergedListener<K, V> {
      override fun entryMerged(event: EntryEvent<K, V?>) {
        context.runOnContext {
          listener(event.key, event.value)
        }
      }
    }, includeValue)
  }

  override fun addPartitionLostListener(listener: () -> Unit) {
    map.addPartitionLostListener {
      context.runOnContext {
        listener()
      }
    }
  }
}
