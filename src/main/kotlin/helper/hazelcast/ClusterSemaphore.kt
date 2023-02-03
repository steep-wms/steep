package helper.hazelcast

import com.hazelcast.cp.ISemaphore
import globalHazelcastInstance
import io.vertx.core.Vertx
import java.util.concurrent.TimeUnit

/**
 * A thin wrapper around Hazelcast's [ISemaphore]
 * @author Michel Kraemer
 */
interface ClusterSemaphore {
  companion object {
    suspend fun create(name: String, permits: Int, vertx: Vertx): ClusterSemaphore {
      val s = ClusterSemaphoreImpl(globalHazelcastInstance.cpSubsystem.getSemaphore(name), vertx)
      s.init(permits)
      return s
    }
  }

  suspend fun acquire()
  suspend fun tryAcquire(): Boolean
  suspend fun tryAcquire(time: Long, unit: TimeUnit): Boolean
  suspend fun release()
}
