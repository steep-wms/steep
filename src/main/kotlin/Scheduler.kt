import AddressConstants.SCHEDULER_LOOKUP_NOW
import ConfigConstants.SCHEDULER_LOOKUP_INTERVAL
import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistryFactory
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
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
import java.time.Instant
import java.util.concurrent.TimeUnit

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

  private lateinit var logFoundProcessChainCache: Cache<String, Boolean>
  private lateinit var logNoAgentCache: Cache<String, Boolean>

  override suspend fun start() {
    log.info("Launching scheduler ...")

    // create registries
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)

    // read configuration
    val lookupInterval = config.getLong(SCHEDULER_LOOKUP_INTERVAL, 20000L)

    // create simple caches to reduce repeated log output
    logFoundProcessChainCache = CacheBuilder.newBuilder()
        .expireAfterAccess(lookupInterval * 10, TimeUnit.MILLISECONDS)
        .maximumSize(1000)
        .build()
    logNoAgentCache = CacheBuilder.newBuilder()
        .expireAfterAccess(lookupInterval * 10, TimeUnit.MILLISECONDS)
        .maximumSize(1000)
        .build()

    // periodically look for new process chains and execute them
    periodicLookupJob = launch {
      while (true) {
        delay(lookupInterval)
        lookup()
      }
    }

    vertx.eventBus().consumer<Int?>(SCHEDULER_LOOKUP_NOW) { msg ->
      val maxLookups = msg.body() ?: Int.MAX_VALUE
      launch {
        lookup(maxLookups)
      }
    }
  }

  override suspend fun stop() {
    log.info("Stopping scheduler ...")
    periodicLookupJob.cancelAndJoin()
    submissionRegistry.close()
  }

  /**
   * Get registered process chains and execute them asynchronously
   * @param maxLookups the maximum number of lookups to perform
   */
  private suspend fun lookup(maxLookups: Int = Int.MAX_VALUE) {
    for (i in 0 until maxLookups) {
      // get next registered process chain
      val processChain = submissionRegistry.fetchNextProcessChain(REGISTERED, RUNNING) ?: return
      logFoundProcessChainCache.get(processChain.id) {
        log.info("Found registered process chain `${processChain.id}'")
        true
      }

      // allocate an agent for the process chain
      val agent = agentRegistry.allocate(processChain)
      if (agent == null) {
        logNoAgentCache.get(processChain.id) {
          log.info("No agent available to execute process chain `${processChain.id}'")
          true
        }
        submissionRegistry.setProcessChainStatus(processChain.id, REGISTERED)
        return
      }
      log.info("Assigned process chain `${processChain.id}' to agent `${agent.id}'")

      // execute process chain
      launch {
        try {
          submissionRegistry.setProcessChainStartTime(processChain.id, Instant.now())
          val results = agent.execute(processChain)
          submissionRegistry.setProcessChainResults(processChain.id, results)
          submissionRegistry.setProcessChainStatus(processChain.id, SUCCESS)
        } catch (t: Throwable) {
          log.error("Process chain execution failed", t)
          submissionRegistry.setProcessChainErrorMessage(processChain.id, t.message)
          submissionRegistry.setProcessChainStatus(processChain.id, ERROR)
        } finally {
          agentRegistry.deallocate(agent)
          submissionRegistry.setProcessChainEndTime(processChain.id, Instant.now())

          // try to lookup next process chain immediately
          vertx.eventBus().send(SCHEDULER_LOOKUP_NOW, 1)
        }
      }
    }
  }
}
