package agent

import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_LEFT
import AddressConstants.REMOTE_AGENT_MISSING
import io.prometheus.client.Gauge
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.LocalMap
import io.vertx.kotlin.core.eventbus.sendAwait
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.removeAwait
import io.vertx.kotlin.core.shareddata.sizeAwait
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

/**
 * An [AgentRegistry] that provides [RemoteAgent]s
 * @author Michel Kraemer
 */
class RemoteAgentRegistry(private val vertx: Vertx) : AgentRegistry, CoroutineScope {
  companion object {
    private val log = LoggerFactory.getLogger(RemoteAgentRegistry::class.java)

    /**
     * Name of a local map keeping information about the remote registry
     */
    private const val LOCAL_MAP_NAME = "RemoteAgentRegistry.Local"

    /**
     * A key in the local map keeping track of whether the remote agent
     * registry has been initialized or not
     */
    private const val KEY_INITIALIZED = "Initialized"

    /**
     * Name of a cluster-wide map keeping IDs of [RemoteAgent]s
     */
    private const val ASYNC_MAP_NAME = "RemoteAgentRegistry.Async"

    /**
     * The current number of registered remote agents
     */
    private val gaugeAgents = Gauge.build()
        .name("jobmanager_remote_agents")
        .help("Number of registered remote agents")
        .register()
  }

  override val coroutineContext: CoroutineContext = vertx.dispatcher()

  /**
   * A local map keeping information about the remote registry
   */
  private val localMap: LocalMap<String, Boolean>

  /**
   * A cluster-wide map keeping IDs of [RemoteAgent]s
   */
  private val agents: Future<AsyncMap<String, Boolean>>

  init {
    // create shared maps
    val sharedData = vertx.sharedData()
    localMap = sharedData.getLocalMap(LOCAL_MAP_NAME)
    agents = Future.future()
    sharedData.getAsyncMap(ASYNC_MAP_NAME, agents)

    // do not register consumers multiple times
    if (localMap.compute(KEY_INITIALIZED) { _, v -> v != null } == false) {
      // log added agents
      vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
        log.info("Remote agent `${msg.body()}' has been added.")
        launch {
          val size = agents.await().sizeAwait()
          log.info("New total number of remote agents: $size")
          gaugeAgents.set(size.toDouble())
        }
      }

      // log left agents
      vertx.eventBus().consumer<String>(REMOTE_AGENT_LEFT) { msg ->
        log.info("Remote agent `${msg.body()}' has left.")
        launch {
          val size = agents.await().sizeAwait()
          log.info("New total number of remote agents: $size")
          gaugeAgents.set(size.toDouble())
        }
      }

      // unregister agents whose nodes have left
      vertx.eventBus().localConsumer<String>(CLUSTER_NODE_LEFT) { msg ->
        launch {
          log.info("Node `${msg.body()}' has left the cluster. Removing remote agent.")
          agents.await().removeAwait(msg.body())
          val address = REMOTE_AGENT_ADDRESS_PREFIX + msg.body()
          vertx.eventBus().publish(REMOTE_AGENT_LEFT, address)
        }
      }
    }
  }

  /**
   * Register a remote agent under the given [id] unless there already is
   * an agent under this [id], in which case the method does nothing. Also
   * announce the availability of the agent in the cluster under the given
   * [id].
   *
   * The agent should already listen to messages on the eventbus address
   * ([REMOTE_AGENT_ADDRESS_PREFIX]` + id`). The agent registry automatically
   * unregisters the agent when the node with the [id] leaves the cluster.
   */
  suspend fun register(id: String) {
    val address = REMOTE_AGENT_ADDRESS_PREFIX + id
    agents.await().putAwait(id, true)
    vertx.eventBus().publish(REMOTE_AGENT_ADDED, address)
  }

  /**
   * Get a list of registered agents
   */
  suspend fun getAgentIds(): Set<String> {
    val agents = this.agents.await()
    return awaitResult { agents.keys(it) }
  }

  override suspend fun selectCandidates(requiredCapabilities: List<Collection<String>>):
      List<Pair<Collection<String>, String>> {
    if (requiredCapabilities.isEmpty()) {
      return emptyList()
    }

    // prepare message
    val requiredCapabilitiesArr = JsonArray(requiredCapabilities
        .map { JsonArray(it.toList()) })
    val msgInquire = json {
      obj(
          "action" to "inquire",
          "requiredCapabilities" to requiredCapabilitiesArr
      )
    }

    val agents = this.agents.await()
    val keys = awaitResult<Set<String>> { agents.keys(it) }

    // ask all agents if they are able and available to execute a process
    // chain with the given required capabilities. collect these agents in
    // a list of candidates
    val candidatesPerSet = mutableMapOf<Int, MutableList<Pair<String, Long>>>()
    for (agent in keys) {
      val address = REMOTE_AGENT_ADDRESS_PREFIX + agent
      try {
        val replyInquire = vertx.eventBus().sendAwait<JsonObject>(address, msgInquire)
        if (replyInquire.body().getBoolean("available")) {
          val lastSequence = replyInquire.body().getLong("lastSequence", -1L)
          val bestRequiredCapabilities = replyInquire.body().getInteger("bestRequiredCapabilities")
          candidatesPerSet.compute(bestRequiredCapabilities) { _, l ->
            val p = Pair(address, lastSequence)
            l?.also { it.add(p) } ?: mutableListOf(p)
          }
        }
      } catch (t: Throwable) {
        log.error("Could not inquire agent `$agent'. Skipping it.", t)
      }
    }

    return candidatesPerSet.mapNotNull { (i, candidates) ->
      // there is no agent that has the required capabilities
      if (candidates.isEmpty()) {
        // publish a message that says that we need an agent with the given
        // capabilities
        val arr = requiredCapabilitiesArr.getJsonArray(i)
        vertx.eventBus().publish(REMOTE_AGENT_MISSING, arr)
        null
      } else {
        // LRU: Select agent with the lowest `lastSequence` because it's the one
        // that has not processed a process chain for the longest time.
        candidates.sortBy { it.second }
        Pair(requiredCapabilities[i], candidates.first().first)
      }
    }
  }

  override suspend fun tryAllocate(address: String): Agent? {
    val msgAllocate = json {
      obj(
          "action" to "allocate"
      )
    }

    try {
      val replyAllocate = vertx.eventBus().sendAwait<String>(address, msgAllocate)
      if (replyAllocate.body() == "ACK") {
        return RemoteAgent(address, vertx)
      }
    } catch (t: Throwable) {
      // fall through
    }

    return null
  }

  override suspend fun deallocate(agent: Agent) {
    val msg = json {
      obj(
          "action" to "deallocate"
      )
    }

    try {
      val reply = vertx.eventBus().sendAwait<String>(agent.id, msg)
      if (reply.body() != "ACK") {
        throw NoStackTraceThrowable("Unknown answer: ${reply.body()}")
      }
    } catch (t: Throwable) {
      log.error("Could not deallocate agent `${agent.id}'", t)
    }
  }
}
