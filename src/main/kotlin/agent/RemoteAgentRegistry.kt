package agent

import AddressConstants
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
import model.processchain.ProcessChain
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
     * Prefix for eventbus addresses of [RemoteAgent]s
     */
    const val AGENT_ADDRESS_PREFIX = "RemoteAgentRegistry.Agent."

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
     * Name of a cluster-wide map keeping nodeIds of [RemoteAgent]s
     */
    private const val ASYNC_MAP_NAME = "RemoteAgentRegistry.Async"
  }

  override val coroutineContext: CoroutineContext = vertx.dispatcher()

  /**
   * A local map keeping information about the remote registry
   */
  private val localMap: LocalMap<String, Boolean>

  /**
   * A cluster-wide map keeping nodeIds of [RemoteAgent]s
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
      vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_ADDED) { msg ->
        log.info("Remote agent `${msg.body()}' has been added.")
        launch {
          log.info("New total number of remote agents: " + agents.await().sizeAwait())
        }
      }

      // log left agents
      vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_LEFT) { msg ->
        log.info("Remote agent `${msg.body()}' has left.")
        launch {
          log.info("New total number of remote agents: " + agents.await().sizeAwait())
        }
      }

      // unregister agents whose nodes have left
      vertx.eventBus().localConsumer<String>(AddressConstants.CLUSTER_NODE_LEFT) { msg ->
        launch {
          log.info("Node `${msg.body()}' has left the cluster. Removing remote agent.")
          agents.await().removeAwait(msg.body())
          val address = AGENT_ADDRESS_PREFIX + msg.body()
          vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_LEFT, address)
        }
      }
    }
  }

  /**
   * Register a remote agent under the given [nodeId] unless there already is
   * an agent under this [nodeId], in which case the method does nothing. Also
   * announce the availability of the agent in the cluster under the given
   * [agentId].
   *
   * The agent should already listen to messages on the eventbus address
   * ([AGENT_ADDRESS_PREFIX]` + nodeId`). The agent registry automatically
   * unregisters the agent when the node with the [nodeId] leaves the cluster.
   */
  suspend fun register(nodeId: String, agentId: String) {
    val address = AGENT_ADDRESS_PREFIX + nodeId
    agents.await().putAwait(nodeId, true)
    vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_ADDED, address)
    vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_AVAILABLE, agentId)
  }

  override suspend fun allocate(processChain: ProcessChain): Agent? {
    val requiredCapabilities = JsonArray()
    processChain.requiredCapabilities.forEach { requiredCapabilities.add(it) }
    val msgInquire = json {
      obj(
          "action" to "inquire",
          "requiredCapabilities" to requiredCapabilities
      )
    }

    val agents = this.agents.await()
    val keys = awaitResult<Set<String>> { agents.keys(it) }
    val skippedAgents = mutableSetOf<String>()

    while (true) {
      // ask all agents if they are able and available to execute a process
      // chain with the given required capabilities. collect these agents in
      // a list of candidates
      val candidates = mutableListOf<Pair<String, Long>>()
      for (agent in keys) {
        val address = AGENT_ADDRESS_PREFIX + agent
        if (skippedAgents.contains(address)) {
          continue
        }
        try {
          val replyInquire = vertx.eventBus().sendAwait<JsonObject>(address, msgInquire)
          if (replyInquire.body().getBoolean("available")) {
            val lastSequence = replyInquire.body().getLong("lastSequence", -1L)
            candidates.add(Pair(address, lastSequence))
          }
        } catch (t: Throwable) {
          log.error("Could not inquire agent `$agent'. Skipping it.", t)
        }
      }

      // there is no agent that has the required capabilities
      if (candidates.isEmpty()) {
        // publish a message that says that we need an agent with the given
        // capabilities
        val arr = JsonArray()
        processChain.requiredCapabilities.forEach { arr.add(it) }
        log.debug("Sending REMOTE_AGENT_MISSING message with: $arr")
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_MISSING, arr)

        return null
      }

      // LRU: Select agent with the lowest `lastSequence` because it's the one
      // that has not processed a process chain for the longest time.
      candidates.sortBy { it.second }
      val result = candidates.first().first

      // try to allocate the agent
      val msgAllocate = json {
        obj(
            "action" to "allocate"
        )
      }
      try {
        val replyAllocate = vertx.eventBus().sendAwait<String>(result, msgAllocate)
        if (replyAllocate.body() == "ACK") {
          return RemoteAgent(result, vertx)
        }
      } catch (t: Throwable) {
        // fall through
      }
      skippedAgents.add(result)
    }
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
