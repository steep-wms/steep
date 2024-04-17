package helper.hazelcast

import com.hazelcast.cp.ISemaphore
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import java.util.concurrent.TimeUnit

/**
 * A thin wrapper around Hazelcast's [ISemaphore]
 * @author Michel Kraemer
 */
class ClusterSemaphoreImpl(private val semaphore: ISemaphore, private val vertx: Vertx) : ClusterSemaphore {
  suspend fun init(permits: Int): Boolean {
    return vertx.executeBlocking({ p ->
      p.complete(semaphore.init(permits))
    }, false).coAwait()
  }

  override suspend fun acquire() {
    vertx.executeBlocking<Unit>({ p ->
      semaphore.acquire()
      p.complete()
    }, false).coAwait()
  }

  override suspend fun tryAcquire(): Boolean {
    return vertx.executeBlocking({ p ->
      p.complete(semaphore.tryAcquire())
    }, false).coAwait()
  }

  override suspend fun tryAcquire(time: Long, unit: TimeUnit): Boolean {
    return vertx.executeBlocking({ p ->
      p.complete(semaphore.tryAcquire(time, unit))
    }, false).coAwait()
  }

  override suspend fun release() {
    vertx.executeBlocking<Unit>({ p ->
      semaphore.release()
      p.complete()
    }, false).coAwait()
  }
}
