import AddressConstants.SCHEDULER_LOOKUP_NOW
import ConfigConstants.SCHEDULER_LOOKUP_INTERVAL
import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus.ERROR
import db.SubmissionRegistry.ProcessChainStatus.REGISTERED
import db.SubmissionRegistry.ProcessChainStatus.RUNNING
import db.SubmissionRegistry.ProcessChainStatus.SUCCESS
import db.SubmissionRegistryFactory
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The scheduler fetches process chains from a [SubmissionRegistry], executes
 * them through [Agent]s, and puts the results back into the
 * [SubmissionRegistry].
 * @author Michel Kraemer
 */
class Scheduler : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(Scheduler::class.java)
  }

  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var agentRegistry: AgentRegistry

  private lateinit var periodicLookupJob: Job

  override suspend fun start() {
    log.info("Launching scheduler ...")

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)

    // read configuration
    val lookupInterval = config.getLong(SCHEDULER_LOOKUP_INTERVAL, 2000L)

    // periodically look for new process chains and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupInterval)
        lookup()
      }
    }

    vertx.eventBus().consumer<Unit>(SCHEDULER_LOOKUP_NOW) {
      launch {
        lookup()
      }
    }
  }

  override suspend fun stop() {
    log.info("Stopping scheduler ...")
    periodicLookupJob.cancelAndJoin()
  }

  /**
   * Get registered process chains and execute them asynchronously
   */
  private suspend fun lookup() {
    while (true) {
      // get next registered process chain
      val processChain = submissionRegistry.fetchNextProcessChain(REGISTERED, RUNNING) ?: return
      log.info("Found registered process chain `${processChain.id}'")

      // allocate an agent for the process chain
      val agent = agentRegistry.allocate(processChain)
      if (agent == null) {
        log.info("No agent available to execute process chain `${processChain.id}'")
        submissionRegistry.setProcessChainStatus(processChain.id, REGISTERED)
        return
      }
      log.info("Assigned process chain `${processChain.id}' to agent `${agent.id}'")

      // execute process chain
      launch {
        try {
          val results = agent.execute(processChain)
          submissionRegistry.setProcessChainResults(processChain.id, results)
          submissionRegistry.setProcessChainStatus(processChain.id, SUCCESS)
        } catch (t: Throwable) {
          submissionRegistry.setProcessChainStatus(processChain.id, ERROR)
        } finally {
          agentRegistry.deallocate(agent)

          // try to lookup next process chain immediately
          vertx.eventBus().send(SCHEDULER_LOOKUP_NOW, null)
        }
      }
    }
  }
}
