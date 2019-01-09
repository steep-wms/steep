package agent

import AddressConstants
import helper.JsonUtils
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.shareddata.AsyncMap
import io.vertx.core.shareddata.LocalMap
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.getLockAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.removeAwait
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
    private const val LOCAL_MAP_AGENT_REGISTRY = "RemoteAgentRegistry.Local"

    /**
     * Name of a cluster-wide map keeping addresses of available [RemoteAgent]s
     */
    private const val ASYNC_MAP_NAME_AVAILABLE = "RemoteAgentRegistry.Available"

    /**
     * Name of a cluster-wide map keeping addresses of busy [RemoteAgent]s
     */
    private const val ASYNC_MAP_NAME_BUSY = "RemoteAgentRegistry.Busy"

    /**
     * Name of a cluster-wide lock used to make atomic operations on the
     * cluster-wide maps
     */
    private const val LOCK_NAME = "RemoteAgentRegistry"

    /**
     * A key in the local map keeping track of whether the remote agent
     * registry has been initialized or not
     */
    private const val KEY_INITIALIZED = "Initialized"
  }

  override val coroutineContext: CoroutineContext = vertx.dispatcher()

  /**
   * A local map keeping information about the remote registry
   */
  private val localMap: LocalMap<String, Boolean>

  /**
   * A cluster-wide map keeping addresses and serialized metadata of
   * available [RemoteAgent]s
   */
  private val availableAgents: Future<AsyncMap<String, String>>

  /**
   * A cluster-wide map keeping addresses and serialized metadata of
   * busy [RemoteAgent]s
   */
  private val busyAgents: Future<AsyncMap<String, String>>

  init {
    // create shared maps
    val sharedData = vertx.sharedData()
    localMap = sharedData.getLocalMap(LOCAL_MAP_AGENT_REGISTRY)
    availableAgents = Future.future()
    busyAgents = Future.future()
    sharedData.getAsyncMap(ASYNC_MAP_NAME_AVAILABLE, availableAgents)
    sharedData.getAsyncMap(ASYNC_MAP_NAME_BUSY, busyAgents)

    // do not register consumers multiple times
    if (localMap.compute(KEY_INITIALIZED) { _, v -> v != null } == false) {
      // log added agents
      vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_ADDED) { msg ->
        log.info("Remote agent `${msg.body()}' has been added.")
        launch {
          logAgents()
        }
      }

      // log left agents
      vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_LEFT) { msg ->
        log.info("Remote agent `${msg.body()}' has left.")
        launch {
          logAgents()
        }
      }

      // unregister agents whose nodes have left
      vertx.eventBus().consumer<String>(AddressConstants.CLUSTER_NODE_LEFT) { msg ->
        launch {
          log.info("Node `${msg.body()}' has left the cluster. Removing remote agents.")
          availableAgents.await().removeAwait(AGENT_ADDRESS_PREFIX + msg.body())
          busyAgents.await().removeAwait(AGENT_ADDRESS_PREFIX + msg.body())
          vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_LEFT, msg.body())
        }
      }
    }
  }

  /**
   * Register a remote agent under the `nodeId` from the given [metadata]
   * object unless there already is an agent under this `nodeId`, in which case
   * the method does nothing. The agent should already listen to messages on
   * the eventbus address ([AGENT_ADDRESS_PREFIX]` + nodeId`). The agent
   * registry automatically unregisters the agent when the node with the
   * `nodeId` leaves the cluster.
   * @param metadata metadata about the remote agent
   */
  suspend fun register(metadata: RemoteAgentMetadata) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_NAME)
    try {
      val availableAgents = this.availableAgents.await()
      val key = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + metadata.nodeId
      val currentMetadata = availableAgents.getAwait(key)
      if (currentMetadata == null) {
        val json = JsonUtils.toJson(metadata)
        availableAgents.putAwait(key, json.encode())
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_ADDED, json)
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun logAgents() {
    val availableAgentKeys = Future.future<MutableSet<String>>()
    val busyAgentKeys = Future.future<MutableSet<String>>()
    availableAgents.await().keys(availableAgentKeys)
    busyAgents.await().keys(busyAgentKeys)

    log.info("Available agents: " + availableAgentKeys.await().map {
      it.substring(AGENT_ADDRESS_PREFIX.length) })
    log.info("Busy agents: " + busyAgentKeys.await().map {
      it.substring(AGENT_ADDRESS_PREFIX.length) })
  }

  override suspend fun allocate(processChain: ProcessChain): Agent? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_NAME)
    try {
      val availableAgents = this.availableAgents.await()
      val keys = awaitResult<Set<String>> { availableAgents.keys(it) }
      if (keys.isEmpty()) {
        return null
      }

      val id = keys.iterator().next()

      val metadata = availableAgents.removeAwait(id)!!
      busyAgents.await().putAwait(id, metadata)

      return RemoteAgent(id, vertx)
    } finally {
      lock.release()
    }
  }

  override suspend fun deallocate(agent: Agent) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_NAME)
    try {
      val metadata = busyAgents.await().removeAwait(agent.id)!!
      availableAgents.await().putAwait(agent.id, metadata)
    } finally {
      lock.release()
    }
  }
}
