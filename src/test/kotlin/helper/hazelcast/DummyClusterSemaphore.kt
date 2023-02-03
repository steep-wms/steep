package helper.hazelcast

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Local implementation of [ClusterMap] that can be used for testing purposes
 * @author Michel Kraemer
 */
class DummyClusterSemaphore : ClusterSemaphore {
  private val semaphore = Semaphore(1)

  @Suppress("BlockingMethodInNonBlockingContext")
  override suspend fun acquire() {
    semaphore.acquire()
  }

  override suspend fun tryAcquire(): Boolean {
    return semaphore.tryAcquire()
  }

  @Suppress("BlockingMethodInNonBlockingContext")
  override suspend fun tryAcquire(time: Long, unit: TimeUnit): Boolean {
    return semaphore.tryAcquire(time, unit)
  }

  override suspend fun release() {
    semaphore.release()
  }
}
