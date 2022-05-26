import AddressConstants.CONTROLLER_LOOKUP_NOW
import AddressConstants.CONTROLLER_LOOKUP_ORPHANS_NOW
import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import ConfigConstants.CONTROLLER_LOOKUP_INTERVAL
import ConfigConstants.CONTROLLER_LOOKUP_MAXERRORS
import ConfigConstants.CONTROLLER_LOOKUP_ORPHANS_INITIAL_DELAY
import ConfigConstants.CONTROLLER_LOOKUP_ORPHANS_INTERVAL
import ConfigConstants.OUT_PATH
import ConfigConstants.TMP_PATH
import com.fasterxml.jackson.databind.SerializationFeature
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.PluginRegistry
import db.PluginRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.toDuration
import io.prometheus.client.Gauge
import io.vertx.core.eventbus.MessageConsumer
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Submission
import model.plugins.call
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.ExecuteAction
import model.workflow.Workflow
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CancellationException

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
    private const val DEFAULT_LOOKUP_MAX_ERRORS = 5L
    private const val DEFAULT_LOOKUP_ORPHANS_INITIAL_DELAY = 0L
    private const val DEFAULT_LOOKUP_ORPHANS_INTERVAL = 300_000L
    private const val PROCESSING_SUBMISSION_LOCK_PREFIX = "Controller.ProcessingSubmission."

    /**
     * The number of generated process chains the controller is waiting for
     */
    private val gaugeProcessChains = Gauge.build()
        .name("steep_controller_process_chains")
        .labelNames("submissionId")
        .help("Number of generated process chains the controller is waiting for")
        .register()
  }

  /**
   * Results produced by [waitForProcessChains]
   * @param results the results of succeeded process chains
   * @param failed the number of failed process chains
   * @param cancelled the number of cancelled process chains
   * @param executedExecutableIds the IDs of all [model.processchain.Executable]s
   * in all process chains that have been executed successfully
   */
  private data class WaitForProcessChainsResult(val results: Map<String, List<Any>>,
      val failed: Int, val cancelled: Int, val executedExecutableIds: Set<String>)

  private lateinit var tmpPath: String
  private lateinit var outPath: String
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var metadataRegistry: MetadataRegistry
  private val pluginRegistry: PluginRegistry = PluginRegistryFactory.create()

  private var lookupInterval: Long = DEFAULT_LOOKUP_INTERVAL
  private var lookupMaxErrors: Long = DEFAULT_LOOKUP_MAX_ERRORS
  private lateinit var periodicLookupJob: Job
  private var lookupOrphansInterval: Long = DEFAULT_LOOKUP_ORPHANS_INTERVAL
  private var periodicLookupOrphansJob: Job? = null
  private var shuttingDown = false

  override suspend fun start() {
    log.info("Launching controller ...")

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    metadataRegistry = MetadataRegistryFactory.create(vertx)

    // read configuration
    tmpPath = config.getString(TMP_PATH) ?: throw IllegalStateException(
        "Missing configuration item `$TMP_PATH'")
    outPath = config.getString(OUT_PATH) ?: throw IllegalStateException(
        "Missing configuration item `$OUT_PATH'")
    if (tmpPath.startsWith(outPath)) {
      throw IllegalStateException("`$TMP_PATH' must not reside inside of " +
          "`$OUT_PATH'. Point both configuration items to different locations.")
    }

    lookupInterval = config.getString(CONTROLLER_LOOKUP_INTERVAL)
        ?.toDuration()?.toMillis() ?: lookupInterval
    lookupMaxErrors = config.getLong(CONTROLLER_LOOKUP_MAXERRORS, lookupMaxErrors)
    lookupOrphansInterval = config.getString(CONTROLLER_LOOKUP_ORPHANS_INTERVAL)
        ?.toDuration()?.toMillis() ?: lookupOrphansInterval
    val lookupOrphansInitialDelay = config.getString(CONTROLLER_LOOKUP_ORPHANS_INITIAL_DELAY)
        ?.toDuration()?.toMillis() ?: DEFAULT_LOOKUP_ORPHANS_INITIAL_DELAY

    // periodically look for new submissions and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupInterval)
        try {
          lookup()
        } catch (t: Throwable) {
          log.error("Failed to look for submissions", t)
        }
      }
    }

    if (lookupOrphansInitialDelay > 0) {
      vertx.setTimer(lookupOrphansInitialDelay) {
        startLookupOrphansJob()
      }
    } else {
      startLookupOrphansJob()
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

  /**
   * Start a periodic job that looks for orphaned submissions and execute
   * it right away.
   */
  private fun startLookupOrphansJob() {
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
  }

  override suspend fun stop() {
    log.info("Stopping controller ...")
    shuttingDown = true
    periodicLookupJob.cancelAndJoin()
    periodicLookupOrphansJob?.cancelAndJoin()
    submissionRegistry.close()
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
      vertx.sharedData().getLockWithTimeout(lockName, 1).await()
    } catch (t: Throwable) {
      // Could not acquire lock. Assume someone else is already processing
      // this submission
      null
    }
  }

  /**
   * Applies all process chain adapter plugins to the given list of
   * [processChains] and returns the modified list. If there are no plugins or
   * if they did no make any modifications, the method returns the original list.
   */
  private suspend fun applyPlugins(processChains: List<ProcessChain>, workflow: Workflow): List<ProcessChain> {
    val adapters = pluginRegistry.getProcessChainAdapters()
    var result = processChains
    for (adapter in adapters) {
      result = adapter.call(result, workflow, vertx)
    }
    return result
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

    var updatePriorityConsumer: MessageConsumer<JsonObject>? = null
    try {
      // check twice - the submission may have already been processed before we
      // were able to acquire the lock
      val actualStatus = submissionRegistry.getSubmissionStatus(submission.id)
      if (actualStatus != Submission.Status.RUNNING) {
        log.debug("Expected submission to be in status `${Submission.Status.RUNNING}' " +
            "but it was in status `$actualStatus'. Skipping execution.")
        return
      }

      val consistencyCheckerPlugins = pluginRegistry.getProcessChainConsistencyCheckers()
      val consistencyChecker: suspend (List<Executable>, ExecuteAction) -> Boolean = { processChain, a ->
        consistencyCheckerPlugins.all { it.call(processChain, a, submission.workflow, vertx) }
      }

      val generator = ProcessChainGenerator(submission.workflow,
          FilenameUtils.normalize("$tmpPath/${submission.id}"),
          FilenameUtils.normalize("$outPath/${submission.id}"),
          metadataRegistry.findServices(), consistencyChecker)

      // update default priority for new process chains if the submission's
      // priority has changed
      updatePriorityConsumer = vertx.eventBus().consumer(AddressConstants.SUBMISSION_PRIORITY_CHANGED) { msg ->
        val id: String? = msg.body().getString("submissionId")
        val priority: Int? = msg.body().getInteger("priority")
        if (id == submission.id && priority != null) {
          generator.defaultPriority = priority
        }
      }

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

        // repeat failed process chains (because we don't know why they failed)
        val failedProcessChains = submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(
            submission.id, ProcessChainStatus.ERROR)
        for (pcId in failedProcessChains) {
          submissionRegistry.setProcessChainStatus(pcId, ProcessChainStatus.REGISTERED)
          submissionRegistry.setProcessChainStartTime(pcId, null)
          submissionRegistry.setProcessChainErrorMessage(pcId, null)
          submissionRegistry.setProcessChainEndTime(pcId, null)
        }

        // Re-load all process chains. waitForProcessChains() will only
        // re-execute those that need to be executed but will collect the output
        // of all process chains so it can be passed to the generator.
        processChainsToResume = submissionRegistry.findProcessChains(
            submission.id).map { it.first }
      }

      // main loop
      var totalProcessChains = 0
      var failed = 0
      var cancelled = 0
      var results = mapOf<String, List<Any>>()
      var executedExecutableIds = setOf<String>()
      val submissionResults = mutableMapOf<String, List<Any>>()
      val processChains = mutableMapOf<String, ProcessChain>()
      val gauge = gaugeProcessChains.labels(submission.id)
      gauge.set(0.0)
      while (true) {
        // generate process chains
        val newProcessChains = if (processChainsToResume != null) {
          val pcs = processChainsToResume
          processChainsToResume = null
          pcs
        } else {
          val pcs = applyPlugins(generator.generate(results, executedExecutableIds),
            submission.workflow)
          if (processChains.isEmpty() && pcs.isEmpty()) {
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
          if (pcs.isNotEmpty()) {
            submissionRegistry.addProcessChains(pcs, submission.id)
          }

          // store the generator's state so we are able to resume the
          // submission later if necessary
          submissionRegistry.setSubmissionExecutionState(submission.id,
              generator.persistState())

          pcs
        }

        newProcessChains.associateByTo(processChains) { it.id }
        gauge.set(processChains.size.toDouble())

        if (newProcessChains.isNotEmpty()) {
          // notify scheduler(s)
          vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)
        }

        // do not keep temporary process chain results in memory if not needed
        val collectTemporaryResults = !generator.isFinished()

        // wait for process chain results
        totalProcessChains += newProcessChains.size
        val w = waitForProcessChains(processChains, collectTemporaryResults)
        results = w.results
        failed += w.failed
        cancelled += w.cancelled
        executedExecutableIds = w.executedExecutableIds
        gauge.set(processChains.size.toDouble())

        // collect submission results
        submissionResults.putAll(results.filter { (_, v) -> hasSubmissionResults(v) })
      }
      gauge.set(0.0)

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
      if (t is CancellationException && shuttingDown) {
        // ignore cancellation on shutdown
        return
      }
      log.error("Could not execute submission", t)
      submissionRegistry.setSubmissionStatus(submission.id, Submission.Status.ERROR)
      submissionRegistry.setSubmissionErrorMessage(submission.id, t.message)
      submissionRegistry.setSubmissionEndTime(submission.id, Instant.now())
      cancelProcessChainsOfSubmission(submission.id)
    } finally {
      if (!shuttingDown) {
        // the submission was either successful or it failed - remove the current
        // execution state so it won't be repeated/resumed
        submissionRegistry.setSubmissionExecutionState(submission.id, null)
      }

      updatePriorityConsumer?.unregister()
      gaugeProcessChains.remove(submission.id)
      lock.release()
    }
  }

  /**
   * Wait for the given [processChains] to finish. If some of them finish
   * earlier than others, remove them from [processChains] and return early.
   * [collectTemporaryResults] of process chains even if they are only
   * temporary, or don't if only submission results should be kept.
   */
  private suspend fun waitForProcessChains(processChains: MutableMap<String, ProcessChain>,
      collectTemporaryResults: Boolean): WaitForProcessChainsResult {
    val results = mutableMapOf<String, List<Any>>()
    var succeeded = 0
    var failed = 0
    var cancelled = 0
    var lookupErrors = 0L
    val executedExecutableIds = mutableSetOf<String>()

    while (succeeded == 0 && failed == 0 && cancelled == 0) {
      delay(lookupInterval)

      val finishedProcessChains = mutableSetOf<String>()
      try {
        val finished = submissionRegistry.getProcessChainStatusAndResultsIfFinished(
            processChains.keys)
        for (f in finished) {
          val processChainId = f.key
          val processChain = processChains[processChainId]!!
          val status = f.value.first
          val pcResults = f.value.second
          when (status) {
            ProcessChainStatus.SUCCESS -> {
              if (pcResults != null) {
                if (collectTemporaryResults) {
                  results.putAll(pcResults)
                } else {
                  results.putAll(pcResults.filterValues { hasSubmissionResults(it) })
                }
              }
              executedExecutableIds.addAll(processChain.executables.map { it.id })
              succeeded++
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

            else -> { /* we will never get here */ }
          }
        }
        lookupErrors = 0
      } catch (t: Throwable) {
        log.warn("Could not look up process chain status", t)
        lookupErrors++
        if (lookupErrors == lookupMaxErrors) {
          throw t
        }
      }

      processChains.keys.removeAll(finishedProcessChains)
    }

    return WaitForProcessChainsResult(results, failed, cancelled, executedExecutableIds)
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

  /**
   * Cancel all process chains of the submission with the given [id]
   */
  private suspend fun cancelProcessChainsOfSubmission(id: String) {
    // first, atomically cancel all process chains that are currently
    // registered but not running yet
    submissionRegistry.setAllProcessChainsStatus(id, ProcessChainStatus.REGISTERED,
      ProcessChainStatus.CANCELLED)

    // now cancel running process chains
    val pcIds = submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(
      id, ProcessChainStatus.RUNNING)
    val cancelMsg = jsonObjectOf("action" to "cancel")
    for (pcId in pcIds) {
      vertx.eventBus().send(LOCAL_AGENT_ADDRESS_PREFIX + pcId, cancelMsg)
    }
  }
}
