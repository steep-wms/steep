import AddressConstants.REMOTE_AGENT_MISSING
import AddressConstants.SCHEDULER_LOOKUP_NOW
import ConfigConstants.SCHEDULER_LOOKUP_INTERVAL
import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistryFactory
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus.CANCELLED
import db.SubmissionRegistry.ProcessChainStatus.ERROR
import db.SubmissionRegistry.ProcessChainStatus.REGISTERED
import db.SubmissionRegistry.ProcessChainStatus.RUNNING
import db.SubmissionRegistry.ProcessChainStatus.SUCCESS
import db.SubmissionRegistryFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.CancellationException
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

  /**
   * A list of sets of capabilities required by process chains with the status
   * [REGISTERED] and the respective number of process chains
   */
  private var allRequiredCapabilities: MutableList<Pair<Collection<String>, Long>> = mutableListOf()
  private var allRequiredCapabilitiesInitialized = false

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
        lookup(updateRequiredCapabilities = true)
      }
    }

    vertx.eventBus().consumer<JsonObject?>(SCHEDULER_LOOKUP_NOW) { msg ->
      val maxLookups = msg.body()?.getInteger("maxLookups") ?: Int.MAX_VALUE
      val updateRequiredCapabilities = msg.body()?.getBoolean("updateRequiredCapabilities") ?: true
      launch {
        lookup(maxLookups, updateRequiredCapabilities)
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
   * @param updateRequiredCapabilities `true` if the list of known required
   * capabilities should be updated before performing the lookup
   */
  private suspend fun lookup(maxLookups: Int = Int.MAX_VALUE,
      updateRequiredCapabilities: Boolean) {
    if (updateRequiredCapabilities || !allRequiredCapabilitiesInitialized) {
      val arcs = submissionRegistry.findProcessChainRequiredCapabilities(REGISTERED)

      // count process chains for each required capability set
      allRequiredCapabilities = arcs.map { rc ->
        rc to submissionRegistry.countProcessChains(status = REGISTERED,
            requiredCapabilities = rc)
      }.toMutableList()
      allRequiredCapabilitiesInitialized = true
    }

    for (i in 0 until maxLookups) {
      // send all known required capabilities to all agents and ask them if they
      // are available and, if so, what required capabilities they can handle
      val candidates = agentRegistry.selectCandidates(allRequiredCapabilities)
      if (candidates.isEmpty()) {
        // Agents are all busy or do not accept our required capabilities.
        // Check if we need to request a new agent.
        val rcsi = allRequiredCapabilities.iterator()
        while (rcsi.hasNext()) {
          val rcs = rcsi.next().first
          if (!submissionRegistry.existsProcessChain(REGISTERED, rcs)) {
            // if there is no such process chain, the capabilities are not
            // required anymore
            rcsi.remove()
          } else {
            // publish a message that says we need an agent with the given
            // capabilities
            val arr = JsonArray(rcs.toList())
            vertx.eventBus().publish(REMOTE_AGENT_MISSING, arr)
          }
        }
        break
      }

      // iterate through all agents that indicated they are available
      for ((requiredCapabilities, address) in candidates) {
        // get next registered process chain for the given set of required capabilities
        val processChain = submissionRegistry.fetchNextProcessChain(
            REGISTERED, RUNNING, requiredCapabilities)
        if (processChain == null) {
          // We didn't find a process chain for these required capabilities.
          // Remove them from the list of known ones.
          allRequiredCapabilities.removeIf { it.first == requiredCapabilities }
          continue
        }

        if (requiredCapabilities.isEmpty()) {
          log.info("Found registered process chain `${processChain.id}'")
        } else {
          log.info("Found registered process chain `${processChain.id}' for " +
              "required capabilities `$requiredCapabilities'")
        }

        // allocate an agent for the process chain
        val agent = agentRegistry.tryAllocate(address)
        if (agent == null) {
          log.warn("Agent with address `$address' did not accept process " +
              "chain `${processChain.id}'")
          submissionRegistry.setProcessChainStatus(processChain.id, REGISTERED)

          // continue with the next capability set and candidate
          continue
        }

        log.info("Assigned process chain `${processChain.id}' to agent `${agent.id}'")

        // execute process chain
        launch {
          try {
            submissionRegistry.setProcessChainStartTime(processChain.id, Instant.now())
            val results = agent.execute(processChain)
            submissionRegistry.setProcessChainResults(processChain.id, results)
            submissionRegistry.setProcessChainStatus(processChain.id, SUCCESS)
          } catch (_: CancellationException) {
            log.warn("Process chain execution was cancelled")
            submissionRegistry.setProcessChainStatus(processChain.id, CANCELLED)
          } catch (t: Throwable) {
            log.error("Process chain execution failed", t)
            submissionRegistry.setProcessChainErrorMessage(processChain.id, t.message)
            submissionRegistry.setProcessChainStatus(processChain.id, ERROR)
          } finally {
            agentRegistry.deallocate(agent)
            submissionRegistry.setProcessChainEndTime(processChain.id, Instant.now())

            // try to lookup next process chain immediately
            vertx.eventBus().send(SCHEDULER_LOOKUP_NOW, json {
              obj(
                  "maxLookups" to 1,
                  "updateRequiredCapabilities" to false
              )
            })
          }
        }
      }
    }
  }
}
