import ConfigConstants.GARBAGECOLLECTOR_CRON
import ConfigConstants.GARBAGECOLLECTOR_RETENTION_SUBMISSIONS
import ConfigConstants.GARBAGECOLLECTOR_RETENTION_VMS
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import db.VMRegistry
import db.VMRegistryFactory
import helper.toDuration
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.quartz.CronExpression
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * The garbage collector removes outdated objects from the registry at a given
 * interval
 * @author Michel Kraemer
 */
class GarbageCollector : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(GarbageCollector::class.java)
  }

  private lateinit var job: Job
  private var retentionSubmissions: Duration? = null
  private var retentionVMs: Duration? = null
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var vmRegistry: VMRegistry

  override suspend fun start() {
    log.info("Launching garbage collector ...")

    val cronStr = config.getString(GARBAGECOLLECTOR_CRON)
        ?: throw IllegalStateException(
            "Missing configuration item `$GARBAGECOLLECTOR_CRON'")
    val cron = CronExpression(cronStr)

    retentionSubmissions = config.getString(GARBAGECOLLECTOR_RETENTION_SUBMISSIONS)?.toDuration()
    retentionVMs = config.getString(GARBAGECOLLECTOR_RETENTION_VMS)?.toDuration()

    // initialize registries
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    vmRegistry = VMRegistryFactory.create(vertx)

    job = launch {
      try {
        while (isActive) {
          val now = Date()
          val next = cron.getNextValidTimeAfter(now)
          val wait = next.time - now.time

          delay(wait)

          try {
            collect()
          } catch (e: CancellationException) {
            // graceful shutdown
            throw e
          } catch (t: Throwable) {
            log.error("Garbage collection failed", t)
          }
        }
      } catch (_: CancellationException) {
        // graceful shutdown
      } catch (t: Throwable) {
        log.error("Garbage collector was aborted", t)
      }
    }
  }

  override suspend fun stop() {
    job.cancelAndJoin()
  }

  private suspend fun collect() {
    log.debug("Collecting garbage ...")

    val now = Instant.now()
    if (retentionSubmissions != null) {
      submissionRegistry.deleteSubmissionsFinishedBefore(now.minus(retentionSubmissions))
    }

    if (retentionVMs != null) {
      vmRegistry.deleteVMsDestroyedBefore(now.minus(retentionVMs))
    }
  }
}
