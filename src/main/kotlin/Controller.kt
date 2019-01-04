import AddressConstants.CONTROLLER_LOOKUP_NOW
import ConfigConstants.CONTROLLER_LOOKUP_INTERVAL
import ConfigConstants.TMP_PATH
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus
import db.SubmissionRegistryFactory
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Submission
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.lang.IllegalStateException

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
  }

  private lateinit var tmpPath: String
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var metadataRegistry: MetadataRegistry

  private var lookupInterval: Long = DEFAULT_LOOKUP_INTERVAL
  private lateinit var periodicLookupJob: Job

  override suspend fun start() {
    log.info("Launching controller ...")

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create()
    metadataRegistry = MetadataRegistryFactory.create(vertx)

    // read configuration
    tmpPath = config.getString(TMP_PATH) ?: throw IllegalStateException(
        "Missing configuration item `$TMP_PATH'")
    lookupInterval = config.getLong(CONTROLLER_LOOKUP_INTERVAL, lookupInterval)

    // periodically look for new submissions and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupInterval)
        lookup()
      }
    }

    vertx.eventBus().consumer<Unit>(CONTROLLER_LOOKUP_NOW) {
      launch {
        lookup()
      }
    }
  }

  override suspend fun stop() {
    log.info("Stopping controller ...")
    periodicLookupJob.cancelAndJoin()
  }

  /**
   * Get new submissions and execute them asynchronously
   */
  private suspend fun lookup() {
    while (true) {
      // get next accepted submission
      val submissions = submissionRegistry.findSubmissionsByStatus(
          Submission.Status.ACCEPTED, 1)
      if (submissions.isEmpty()) {
        // no new submissions found
        return
      }
      val submission = submissions.first()
      log.info("Found new submission `${submission.id}'")

      // set status to RUNNING before launching a new job to avoid race conditions
      submissionRegistry.setSubmissionStatus(submission.id, Submission.Status.RUNNING)

      // execute submission asynchronously
      launch {
        runSubmission(submission)
      }
    }
  }

  /**
   * Execute a submission
   * @param submission the submission to execute
   */
  private suspend fun runSubmission(submission: Submission) {
    val ruleSystem = RuleSystem(submission.workflow, tmpPath,
        metadataRegistry.findServices())

    var totalProcessChains = 0
    var errors = 0
    var results = mapOf<String, List<String>>()
    while (true) {
      // generate process chains
      val processChains = ruleSystem.fire(results)
      if (processChains.isEmpty()) {
        break
      }

      // store process chains in submission registry
      for (processChain in processChains) {
        submissionRegistry.addProcessChain(processChain, submission.id)
      }

      // wait for process chain results
      totalProcessChains += processChains.size
      val w = waitForProcessChains(processChains)
      results = w.first
      errors += w.second
    }

    if (ruleSystem.isFinished()) {
      val status = when (errors) {
        0 -> Submission.Status.SUCCESS
        totalProcessChains -> Submission.Status.ERROR
        else -> Submission.Status.PARTIAL_SUCCESS
      }
      submissionRegistry.setSubmissionStatus(submission.id, status)
    } else {
      submissionRegistry.setSubmissionStatus(submission.id, Submission.Status.ERROR)
    }
  }

  /**
   * Wait for the given list of process chains to finish
   * @param processChains the process chains to wait for
   * @return a pair containing the accumulated process chain results and the
   * number of failed process chains
   */
  private suspend fun waitForProcessChains(processChains: List<ProcessChain>):
      Pair<Map<String, List<String>>, Int> {
    val results = mutableMapOf<String, List<String>>()
    var errors = 0
    var finished = 0

    while (finished < processChains.size) {
      delay(lookupInterval)

      for (processChain in processChains) {
        val status = submissionRegistry.getProcessChainStatus(processChain.id)
        when (status) {
          ProcessChainStatus.SUCCESS -> {
            submissionRegistry.getProcessChainOutput(processChain.id)?.let {
              results.putAll(it)
            }
            finished++
          }

          ProcessChainStatus.ERROR -> {
            errors++
            finished++
          }

          else -> {}
        }
      }
    }

    return Pair(results, errors)
  }
}
