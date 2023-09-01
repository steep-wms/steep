package helper.hazelcast

import ConfigConstants
import com.hazelcast.splitbrainprotection.SplitBrainProtectionEvent
import com.hazelcast.splitbrainprotection.SplitBrainProtectionListener
import helper.toDuration
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * A split-brain protection listener that can shut the Steep instance down
 * after a configured amount of time when a split-brain situation has been
 * detected and if it has not been resolved in the meantime.
 * @param config the application's configuration
 * @author Michel Kraemer
 */
class ExitingSplitBrainProtectionListener(config: JsonObject) : SplitBrainProtectionListener {
  companion object {
    private val log = LoggerFactory.getLogger(ExitingSplitBrainProtectionListener::class.java)
  }

  private val scheduler = ScheduledThreadPoolExecutor(0)
  private var scheduledFuture: ScheduledFuture<*>? = null
  private val timeoutMillis = config.getString(
      ConfigConstants.CLUSTER_HAZELCAST_SPLITBRAINPROTECTION_EXITPROCESSAFTER)
      ?.toDuration()?.toMillis()

  override fun onChange(splitBrainProtectionEvent: SplitBrainProtectionEvent) {
    if (!splitBrainProtectionEvent.isPresent) {
      log.warn("A split-brain situation has been detected!")
      if (timeoutMillis != null) {
        if (timeoutMillis == 0L) {
          // shut down immediately
          exitProcess(16)
        } else if (scheduledFuture == null) {
          // shut down after timeout
          scheduledFuture = scheduler.schedule({
            log.error("Split-brain protection timeout exceeded. Shutting down ...")
            exitProcess(16)
          }, timeoutMillis, TimeUnit.MILLISECONDS)
        }
      }
    } else {
      log.info("The split-brain situation has been resolved.")
      scheduledFuture?.cancel(false)
      scheduledFuture = null
    }
  }
}
