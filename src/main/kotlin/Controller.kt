import AddressConstants.CONTROLLER_LOOKUP_NOW
import AddressConstants.CONTROLLER_LOOKUP_ORPHANS_NOW
import ConfigConstants.CONTROLLER_LOOKUP_INTERVAL
import ConfigConstants.CONTROLLER_LOOKUP_ORPHANS_INTERVAL
import ConfigConstants.OUT_PATH
import ConfigConstants.TMP_PATH
import com.fasterxml.jackson.databind.SerializationFeature
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.RuleRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import helper.JsonUtils
import io.prometheus.client.Gauge
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.core.shareddata.getLockWithTimeoutAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Submission
import model.processchain.ProcessChain
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The controller fetches submissions from a [SubmissionRegistry], converts
 * them to process chains, sends these chains to the [Scheduler] to execute
 * them, and finally and puts the results back into the [SubmissionRegistry].
 * @author Michel Kraemer
 */
class Controller : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(Controller::class.java)
    private const val DEFAULT_LOOKUP_INTERVAL = 2000L
    private const val DEFAULT_LOOKUP_ORPHANS_INTERVAL = 300_000L
    private const val PROCESSING_SUBMISSION_LOCK_PREFIX = "Controller.ProcessingSubmission."

    /**
     * The current number of process chains we are currently waiting for
     */
    private val gaugeProcessChains = Gauge.build()
        .name("jobmanager_controller_process_chains")
        .help("Number of process chains the controller is waiting for")
        .register()
  }

  /**
   * Results produced by [waitForProcessChains]
   * @param results the results of suceeded process chains
   * @param failed the number of failed process chains
   * @param cancelled the number of cancelled process chains
   */
  private data class WaitForProcessChainsResult(val results: Map<String, List<Any>>,
      val failed: Int, val cancelled: Int)

  private lateinit var tmpPath: String
  private lateinit var outPath: String
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var ruleSystem: RuleSystem

  private var lookupInterval: Long = DEFAULT_LOOKUP_INTERVAL
  private lateinit var periodicLookupJob: Job
  private var lookupOrphansInterval: Long = DEFAULT_LOOKUP_ORPHANS_INTERVAL
  private lateinit var periodicLookupOrphansJob: Job

  override suspend fun start() {
    log.info("Launching controller ...")

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    metadataRegistry = MetadataRegistryFactory.create(vertx)

    // prepare rule system
    val ruleRegistry = RuleRegistryFactory.create(vertx)
    ruleSystem = RuleSystem(ruleRegistry.findRules())

    // read configuration
    tmpPath = config.getString(TMP_PATH) ?: throw IllegalStateException(
        "Missing configuration item `$TMP_PATH'")
    outPath = config.getString(OUT_PATH) ?: throw IllegalStateException(
        "Missing configuration item `$OUT_PATH'")
    if (tmpPath.startsWith(outPath)) {
      throw IllegalStateException("`$TMP_PATH' must not reside inside of " +
          "`$OUT_PATH'. Point both configuration items to different locations.")
    }

    lookupInterval = config.getLong(CONTROLLER_LOOKUP_INTERVAL, lookupInterval)
    lookupOrphansInterval = config.getLong(CONTROLLER_LOOKUP_ORPHANS_INTERVAL,
        lookupOrphansInterval)

    // periodically look for new submissions and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupInterval)
        lookup()
      }
    }

    // periodically look for orphaned running submissions and re-execute them
    periodicLookupOrphansJob = launch {
      while (true) {
        delay(lookupOrphansInterval)
        lookupOrphans()
      }
    }
    launch {
      // look up for orphaned running submissions now
      lookupOrphans()
    }

    vertx.eventBus().consumer<Unit>(CONTROLLER_LOOKUP_NOW) {
      launch {
        lookup()
      }
    }

    vertx.eventBus().consumer<Unit>(CONTROLLER_LOOKUP_ORPHANS_NOW) {
      launch {
        lookupOrphans()
      }
    }
  }

  override suspend fun stop() {
    log.info("Stopping controller ...")
    periodicLookupJob.cancelAndJoin()
    periodicLookupOrphansJob.cancelAndJoin()
    submissionRegistry.close()
    ruleSystem.close()
  }

  /**
   * Get new submissions and execute them asynchronously
   */
  private suspend fun lookup() {
    while (true) {
      // get next submission
      val submission = submissionRegistry.fetchNextSubmission(
          Submission.Status.ACCEPTED, Submission.Status.RUNNING) ?: return
      log.info("Found new submission `${submission.id}'")

      // execute submission asynchronously
      launch {
        runSubmission(submission)
      }
    }
  }

  /**
   * Check for orphaned running submissions and resume their execution
   */
  private suspend fun lookupOrphans() {
    val ids = submissionRegistry.findSubmissionIdsByStatus(Submission.Status.RUNNING)
    for (id in ids) {
      val lock = tryLockSubmission(id)
      if (lock != null) {
        lock.release()
        val s = submissionRegistry.findSubmissionById(id)
        if (s != null) {
          log.info("Found orphaned running submission `$id'. Trying to resume ...")
          launch {
            runSubmission(s)
          }
        }
      }
    }
  }

  /**
   * Tries to create a lock for the submission with the given [submissionId].
   * As long as the lock is held, the submission is being processed. The method
   * returns `null` if the lock could not be acquired. The reason for this is
   * most likely that the submission is already being processed.
   */
  private suspend fun tryLockSubmission(submissionId: String): Lock? {
    val lockName = PROCESSING_SUBMISSION_LOCK_PREFIX + submissionId
    return try {
      vertx.sharedData().getLockWithTimeoutAwait(lockName, 1)
    } catch (t: Throwable) {
      // Could not acquire lock. Assume someone else is already processing
      // this submission
      null
    }
  }

  /**
   * Execute a submission
   * @param submission the submission to execute
   */
  private suspend fun runSubmission(submission: Submission) {
    val lock = tryLockSubmission(submission.id)
    if (lock == null) {
      log.debug("The submission `${submission.id}' is already being executed")
      return
    }

    try {
      // check twice - the submission may have already been processed before we
      // were able to acquire the lock
      val actualStatus = submissionRegistry.getSubmissionStatus(submission.id)
      if (actualStatus != Submission.Status.RUNNING) {
        log.debug("Expected submission to be in status `${Submission.Status.RUNNING}' " +
            "but it was in status `$actualStatus'. Skipping execution.")
        return
      }

      val generator = ProcessChainGenerator(submission.workflow,
          FilenameUtils.normalize("$tmpPath/${submission.id}"),
          FilenameUtils.normalize("$outPath/${submission.id}"),
          metadataRegistry.findServices())

      // check if submission needs to be resumed
      var processChainsToResume: Collection<ProcessChain>? = null
      val executionState = submissionRegistry.getSubmissionExecutionState(submission.id)
      if (executionState == null) {
        // submission has not been started before - start it now
        submissionRegistry.setSubmissionStartTime(submission.id, Instant.now())
      } else {
        log.info("Resuming submission `${submission.id}' ...")

        // resume aborted submissions...
        // load generator state
        generator.loadState(executionState)

        // reset running process chains and repeat failed process chains
        val runningProcessChains = submissionRegistry.countProcessChainsByStatus(submission.id,
            ProcessChainStatus.RUNNING)
        val failedProcessChains = submissionRegistry.countProcessChainsByStatus(submission.id,
            ProcessChainStatus.ERROR)
        if (runningProcessChains > 0 || failedProcessChains > 0) {
          val pcstatuses = submissionRegistry.findProcessChainStatusesBySubmissionId(submission.id)
          for ((pcId, pcstatus) in pcstatuses) {
            if (pcstatus === ProcessChainStatus.RUNNING || pcstatus === ProcessChainStatus.ERROR) {
              submissionRegistry.setProcessChainStatus(pcId, ProcessChainStatus.REGISTERED)
              submissionRegistry.setProcessChainStartTime(pcId, null)
            }
            if (pcstatus === ProcessChainStatus.ERROR) {
              submissionRegistry.setProcessChainErrorMessage(pcId, null)
              submissionRegistry.setProcessChainEndTime(pcId, null)
            }
          }
        }

        // Re-load all process chains. waitForProcessChains() will only
        // re-execute those that need to be executed but will collect the output
        // of all process chains so it can be passed to the generator.
        processChainsToResume = submissionRegistry.findProcessChainsBySubmissionId(submission.id)
      }

      // main loop
      var totalProcessChains = 0
      var failed = 0
      var cancelled = 0
      var results = mapOf<String, List<Any>>()
      val submissionResults = mutableMapOf<String, List<Any>>()
      while (true) {
        // generate process chains
        val processChains = if (processChainsToResume != null) {
          val pcs = processChainsToResume
          processChainsToResume = null
          pcs
        } else {
          val pcs = ruleSystem.apply(generator.generate(results))
          if (pcs.isEmpty()) {
            break
          }

          // log first 100 lines of process chains
          if (log.isDebugEnabled) {
            val mapper = JsonUtils.mapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
            val lines = mutableListOf<String>()
            var i = 0
            while (lines.size < 100 && i < pcs.size) {
              val serializedProcessChain = mapper.writeValueAsString(pcs[i])
              lines.addAll(serializedProcessChain.lineSequence().take(101).toList())
              ++i
            }
            if (lines.size <= 100) {
              log.debug("Generated process chains:\n" + lines.joinToString("\n"))
            } else {
              log.debug("Generated process chains (first 100 lines):\n" +
                  lines.take(100).joinToString("\n") + "\n...")
            }
          }

          // store process chains in submission registry
          submissionRegistry.addProcessChains(pcs, submission.id)

          // store the generator's state so we are able to resume the
          // submission later if necessary
          submissionRegistry.setSubmissionExecutionState(submission.id,
              generator.persistState())

          pcs
        }

        // notify scheduler(s)
        vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)

        // wait for process chain results
        totalProcessChains += processChains.size
        val w = waitForProcessChains(processChains)
        results = w.results
        failed += w.failed
        cancelled += w.cancelled

        // collect submission results
        submissionResults.putAll(results.filter { (_, v) -> hasSubmissionResults(v) })
      }

      // evaluate results
      val (status, errorMessage) = if (generator.isFinished()) {
        when (cancelled) {
          0 -> when (failed) {
            0 -> Submission.Status.SUCCESS to null
            totalProcessChains -> Submission.Status.ERROR to "All process chains failed"
            else -> Submission.Status.PARTIAL_SUCCESS to null
          }
          else -> Submission.Status.CANCELLED to null
        }
      } else {
        if (cancelled > 0) {
          Submission.Status.CANCELLED to null
        } else {
          val em = when (failed) {
            0 -> "Submission was not executed completely. There is at least " +
                "one action in the workflow that has not been executed because " +
                "its input was not available."
            1 -> "Submission was not executed completely because a process " +
                "chain failed"
            else -> "Submission was not executed completely because $failed " +
                "process chains failed"
          }
          log.error(em)
          Submission.Status.ERROR to em
        }
      }

      val logMsg = "Submission `${submission.id}' finished with status $status"
      when (status) {
        Submission.Status.PARTIAL_SUCCESS -> log.warn(logMsg)
        Submission.Status.ERROR -> log.error(logMsg)
        else -> log.info(logMsg)
      }

      if (submissionResults.isNotEmpty()) {
        submissionRegistry.setSubmissionResults(submission.id, submissionResults)
      }
      submissionRegistry.setSubmissionStatus(submission.id, status)
      if (errorMessage != null) {
        submissionRegistry.setSubmissionErrorMessage(submission.id, errorMessage)
      }
      submissionRegistry.setSubmissionEndTime(submission.id, Instant.now())
    } catch (t: Throwable) {
      log.error("Could not execute submission", t)
      submissionRegistry.setSubmissionStatus(submission.id, Submission.Status.ERROR)
      submissionRegistry.setSubmissionErrorMessage(submission.id, t.message)
    } finally {
      // the submission was either successful or it failed - remove the current
      // execution state so it won't be repeated/resumed
      submissionRegistry.setSubmissionExecutionState(submission.id, null)

      lock.release()
    }
  }

  /**
   * Wait for the given list of process chains to finish
   * @param processChains the process chains to wait for
   * @return an object containing the accumulated process chain results as well
   * as the number of failed and cancelled process chains
   */
  private suspend fun waitForProcessChains(processChains: Collection<ProcessChain>):
      WaitForProcessChainsResult {
    val results = mutableMapOf<String, List<Any>>()
    var failed = 0
    var cancelled = 0

    val processChainsToCheck = processChains.map { it.id }.toMutableSet()
    gaugeProcessChains.inc(processChainsToCheck.size.toDouble())
    try {
      while (processChainsToCheck.isNotEmpty()) {
        delay(lookupInterval)

        val finishedProcessChains = mutableSetOf<String>()
        loop@ for (processChainId in processChainsToCheck) {
          when (submissionRegistry.getProcessChainStatus(processChainId)) {
            ProcessChainStatus.SUCCESS -> {
              submissionRegistry.getProcessChainResults(processChainId)?.let {
                results.putAll(it)
              }
              finishedProcessChains.add(processChainId)
            }

            ProcessChainStatus.ERROR -> {
              failed++
              finishedProcessChains.add(processChainId)
            }

            ProcessChainStatus.CANCELLED -> {
              cancelled++
              finishedProcessChains.add(processChainId)
            }

            else -> {
              // since we're waiting for all process chains to finish anyhow, we
              // can stop looking as soon as we find a process chain that has not
              // finished yet and wait for the next interval
              break@loop
            }
          }
        }

        processChainsToCheck.removeAll(finishedProcessChains)
        gaugeProcessChains.dec(finishedProcessChains.size.toDouble())
      }
    } finally {
      gaugeProcessChains.dec(processChainsToCheck.size.toDouble())
    }

    return WaitForProcessChainsResult(results, failed, cancelled)
  }

  /**
   * Recursively check if the given [results] contain strings that start with [outPath]
   */
  private fun hasSubmissionResults(results: Iterable<*>): Boolean = results.any {
    when (it) {
      is String -> it.startsWith(outPath)
      is Iterable<*> -> hasSubmissionResults(it)
      else -> false
    }
  }
}
