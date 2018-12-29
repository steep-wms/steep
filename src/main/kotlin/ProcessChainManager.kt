import AddressConstants.PCM_LOOKUP_NOW
import ConfigConstants.PCM_LOOKUP_INTERVAL
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
 * The process chain manager fetches process chains from a [SubmissionRegistry],
 * executes them through [Agent]s, and puts the results back into the
 * [SubmissionRegistry].
 * @author Michel Kraemer
 */
class ProcessChainManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(ProcessChainManager::class.java)
  }

  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var agentRegistry: AgentRegistry

  private lateinit var periodicLookupJob: Job

  override suspend fun start() {
    log.info("Launching process chain manager ...")

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create()
    agentRegistry = AgentRegistryFactory.create()

    // read configuration
    val lookupNextProcessChainInterval = config.getLong(PCM_LOOKUP_INTERVAL, 2000L)

    // periodically look for new process chains and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupNextProcessChainInterval)
        lookupNextProcessChains()
      }
    }

    vertx.eventBus().consumer<Unit>(PCM_LOOKUP_NOW) {
      launch {
        lookupNextProcessChains()
      }
    }
  }

  override suspend fun stop() {
    log.info("Stopping process chain manager ...")
    periodicLookupJob.cancelAndJoin()
  }

  private suspend fun lookupNextProcessChains() {
    while (true) {
      log.debug("Looking up next process chain ...")

      // get next registered process chain
      val processChains = submissionRegistry.findProcessChainsByStatus(REGISTERED, 1)
      if (processChains.isEmpty()) {
        // no registered process chains found
        return
      }
      val processChain = processChains[0]
      log.info("Found registered process chain `${processChain.id}'")

      // allocate an agent for the process chain
      val agent = agentRegistry.allocate(processChain)
      if (agent == null) {
        log.info("No agent available to execute process chain `${processChain.id}'")
        return
      }
      log.info("Assigned process chain `${processChain.id}' to agent `${agent.id}'")

      // execute process chain
      launch {
        try {
          submissionRegistry.setProcessChainStatus(processChain.id, RUNNING)
          val results = agent.execute(processChain)
          submissionRegistry.setProcessChainOutput(processChain.id, results)
          submissionRegistry.setProcessChainStatus(processChain.id, SUCCESS)
        } catch (t: Throwable) {
          submissionRegistry.setProcessChainStatus(processChain.id, ERROR)
        } finally {
          agentRegistry.deallocate(agent)
        }
      }
    }
  }
}
