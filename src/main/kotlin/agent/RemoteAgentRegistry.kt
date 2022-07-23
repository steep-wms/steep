package agent

import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_LEFT
import agent.AgentRegistry.SelectCandidatesParam
import helper.JsonUtils
import helper.debounce
import helper.hazelcast.ClusterMap
import io.prometheus.client.Gauge
import io.vertx.core.Vertx
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.LocalMap
import io.vertx.core.shareddata.Shareable
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.await
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
     * Name of a local map keeping known lastSequence numbers for each agent
     */
    private const val LOCAL_AGENT_SEQUENCE_CACHE_NAME =
        "RemoteAgentRegistry.LocalAgentSequenceCache"

    /**
     * Name of a local map keeping agent capabilities
     */
    private const val LOCAL_AGENT_CAPABILITIES_CACHE_NAME =
        "RemoteAgentRegistry.LocalAgentCapabilitiesCache"

    /**
     * Name of a local map keeping IDs of agents we allocated
     */
    private const val LOCAL_ALLOCATED_AGENTS_CACHE_NAME =
        "RemoteAgentRegistry.LocalAllocatedAgentsCache"

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
        .name("steep_remote_agents")
        .help("Number of registered remote agents")
        .register()
  }

  /**
   * A wrapper class that implements [Shareable] so we can put an immutable
   * set of required capabilities into the local map [agentCapabilitiesCache]
   */
  private class CachedCapabilities(val rcs: Set<String>) : Shareable

  override val coroutineContext: CoroutineContext = vertx.dispatcher()

  /**
   * A local map keeping information about the remote registry
   */
  private val localMap: LocalMap<String, Boolean>

  /**
   * A local map keeping known lastSequence numbers for each agent
   */
  private val agentSequenceCache: LocalMap<String, Long>

  /**
   * A local map keeping agent capabilities
   */
  private val agentCapabilitiesCache: LocalMap<String, CachedCapabilities>

  /**
   * A local map that keeps IDs of agents we allocated. This is used to reduce
   * the number of inquiries sent to agents. We do not have to send an inquiry
   * when we know the agent is currently busy because it has been allocated
   * by us. We will still sent inquiries to agents allocated by other
   * (non-local) remote agent registries running somewhere else in the cluster.
   */
  private val allocatedAgentsCache: LocalMap<String, Boolean>

  /**
   * A cluster-wide map keeping IDs of [RemoteAgent]s
   */
  private val agents: ClusterMap<String, Boolean>

  init {
    // create shared maps
    val sharedData = vertx.sharedData()
    localMap = sharedData.getLocalMap(LOCAL_MAP_NAME)
    agentSequenceCache = sharedData.getLocalMap(LOCAL_AGENT_SEQUENCE_CACHE_NAME)
    agentCapabilitiesCache = sharedData.getLocalMap(LOCAL_AGENT_CAPABILITIES_CACHE_NAME)
    allocatedAgentsCache = sharedData.getLocalMap(LOCAL_ALLOCATED_AGENTS_CACHE_NAME)
    agents = ClusterMap.create(ASYNC_MAP_NAME, vertx)

    // do not register listeners and consumers multiple times
    if (localMap.compute(KEY_INITIALIZED) { _, v -> v != null } == false) {
      val reportRemoteAgents = debounce(vertx) {
        val size = agents.size()
        log.info("New total number of remote agents: $size")
        gaugeAgents.set(size.toDouble())
      }

      val onEntryAdded: (String, Boolean?) -> Unit = { k, _ ->
        log.info("Remote agent `$k' has been added.")
        reportRemoteAgents()

        // Send REMOTE_AGENT_ADDED message. We only need to deliver it to
        // local consumers because every node in the cluster has an
        // EntryAddedListener like this.
        vertx.eventBus().publish(REMOTE_AGENT_ADDED, k, deliveryOptionsOf(localOnly = true))
      }

      agents.addEntryAddedListener(false, onEntryAdded)
      agents.addEntryMergedListener(false, onEntryAdded)

      // unregister agents whose nodes have left
      vertx.eventBus().localConsumer<JsonObject>(CLUSTER_NODE_LEFT) { msg ->
        launch {
          val agentId = msg.body().getString("agentId")
          val instances = msg.body().getInteger("instances", 1)

          log.info("Node `${agentId}' has left the cluster.")

          for (i in 1..instances) {
            val id = if (i == 1) agentId else "$agentId[$i]"
            deregister(id)
          }
        }
      }

      agents.addEntryRemovedListener { k ->
        agentSequenceCache.remove(k)
        agentCapabilitiesCache.remove(k)
        allocatedAgentsCache.remove(k)

        log.info("Remote agent `$k' has left.")
        reportRemoteAgents()

        // Send REMOTE_AGENT_LEFT message. We only need to deliver it to
        // local consumers because every node in the cluster has an
        // EntryRemovedListener like this.
        vertx.eventBus().publish(REMOTE_AGENT_LEFT, k, deliveryOptionsOf(localOnly = true))
      }

      val onPartitionLost = debounce(vertx) {
        // data has been lost in the cluster
        // clear caches
        agentSequenceCache.clear()
        agentCapabilitiesCache.clear()
        allocatedAgentsCache.clear()

        reportRemoteAgents()
      }

      agents.addPartitionLostListener {
        onPartitionLost()
      }
    }
  }

  /**
   * Register a remote agent under the given [id] unless there already is
   * an agent under this [id], in which case the method does nothing.
   *
   * The agent should already listen to messages on the eventbus address
   * ([REMOTE_AGENT_ADDRESS_PREFIX]` + id`). The agent registry automatically
   * deregisters the agent when the node with the [id] leaves the cluster.
   */
  suspend fun register(id: String) {
    agents.putIfAbsent(id, true)
  }

  /**
   * Remove the remote agent with the given [id] from the registry
   */
  suspend fun deregister(id: String) {
    agents.delete(id)
  }

  override suspend fun getAgentIds(): Set<String> {
    return agents.keys()
  }

  override suspend fun getPrimaryAgentIds(): Set<String> =
      getAgentIds().filter { !it.matches(""".+\[\d+]""".toRegex()) }.toSet()

  /**
   * Get list of agent IDs and their lastSequence number (sorted by
   * lastSequence number)
   */
  private fun getAgentSequences(agentIds: Collection<String>): List<Pair<String, Long>> {
    val result = mutableListOf<Pair<String, Long>>()

    for (agent in agentIds) {
      val sequence = agentSequenceCache[agent] ?: -1
      result.add(Pair(agent, sequence))
    }

    result.sortBy { it.second }

    return result
  }

  override suspend fun selectCandidates(params: List<SelectCandidatesParam>):
      List<Pair<Collection<String>, String>> {
    if (params.isEmpty()) {
      return emptyList()
    }

    val msgInquire = jsonObjectOf(
        "action" to "inquire",
        "params" to JsonUtils.mapper.convertValue(params, List::class.java)
    )

    // get IDs of agents that are not busy at the moment
    val filteredAgentIds = getAgentIds().filter { !allocatedAgentsCache.contains(it) }

    // get last sequence number of all agents (if known)
    val keys = getAgentSequences(filteredAgentIds)

    // ask all agents if they are able and available to execute a process
    // chain with the given required capabilities. collect these agents in
    // a list of candidates
    var lastCandidateSequence = -1L
    val candidatesPerSet = mutableMapOf<Int, MutableList<Pair<String, Long>>>()
    for (agentAndSequence in keys) {
      if (candidatesPerSet.size == params.size &&
          agentAndSequence.second >= lastCandidateSequence) {
        // We do not have to inquire the other agents. We already found at
        // least one agent for each required capability set and there will be
        // none that has a lower lastSequence number.
        break
      }

      val agent = agentAndSequence.first
      val address = REMOTE_AGENT_ADDRESS_PREFIX + agent

      // check if the agent actually needs to be inquired based on the
      // capabilities we know it has
      val supportedCapabilities = agentCapabilitiesCache[agent]?.rcs
      if (supportedCapabilities != null) {
        var shouldInquire = false
        for ((pi, ps) in params.withIndex()) {
          if (candidatesPerSet.containsKey(pi) && agentAndSequence.second >= lastCandidateSequence) {
            // we already have a candidate for this capability set and this
            // agent's lastSequence would definitely be higher
            continue
          }
          if (supportedCapabilities.containsAll(ps.requiredCapabilities)) {
            // the agent supports at least one required capabilities set
            shouldInquire = true
            break
          }
        }

        if (!shouldInquire) {
          continue
        }

        // It's not necessary to include the capabilities in the response. We
        // already know them.
        msgInquire.remove("includeCapabilities")
      } else {
        // We don't know what capabilities the agent has. It should tell us.
        msgInquire.put("includeCapabilities", true)
      }

      // inquire agent
      try {
        val replyInquire = vertx.eventBus().request<JsonObject>(address, msgInquire).await()
        val lastSequence = replyInquire.body().getLong("lastSequence", -1L)

        // check if the agent is available
        if (replyInquire.body().getBoolean("available")) {
          val bestRequiredCapabilities = replyInquire.body().getInteger("bestRequiredCapabilities")
          candidatesPerSet.compute(bestRequiredCapabilities) { _, l ->
            val p = Pair(address, lastSequence)
            l?.also { it.add(p) } ?: mutableListOf(p)
          }

          lastCandidateSequence = lastSequence

          // Pretend we already assigned EXACTLY ONE process chain with this
          // capability set to this agent so that other agents maybe chose another set.
          msgInquire.getJsonArray("params")
              .getJsonObject(bestRequiredCapabilities)
              .put("count", params[bestRequiredCapabilities].count - 1)
        }

        // save capabilities this agent supports if they are included in the response
        if (supportedCapabilities == null) {
          val actuallySupportedCapabilities = replyInquire.body().getJsonArray("capabilities")
          if (actuallySupportedCapabilities != null) {
            val cachedCapabilities = mutableSetOf<String>()
            actuallySupportedCapabilities.forEach { cachedCapabilities.add(it as String) }
            agentCapabilitiesCache[agent] = CachedCapabilities(cachedCapabilities)
          }
        }

        agentSequenceCache[agent] = lastSequence
      } catch (t: Throwable) {
        log.error("Could not inquire agent `$agent'. Skipping it.", t)
      }
    }

    return candidatesPerSet.map { (i, candidates) ->
      // LRU: Select agent with the lowest `lastSequence` because it's the one
      // that has not processed a process chain for the longest time.
      candidates.sortBy { it.second }
      Pair(params[i].requiredCapabilities, candidates.first().first)
    }
  }

  override suspend fun tryAllocate(address: String, processChainId: String): Agent? {
    val msgAllocate = json {
      obj(
          "action" to "allocate",
          "processChainId" to processChainId
      )
    }

    try {
      val replyAllocate = vertx.eventBus().request<String>(address, msgAllocate).await()
      if (replyAllocate.body() == "ACK") {
        val agentId = address.substring(REMOTE_AGENT_ADDRESS_PREFIX.length)
        allocatedAgentsCache[agentId] = true
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
      val reply = vertx.eventBus().request<String>(agent.id, msg).await()
      if (reply.body() != "ACK") {
        throw NoStackTraceThrowable("Unknown answer: ${reply.body()}")
      }
    } catch (t: Throwable) {
      log.error("Could not deallocate agent `${agent.id}'", t)
    } finally {
      val agentId = agent.id.substring(REMOTE_AGENT_ADDRESS_PREFIX.length)
      allocatedAgentsCache.remove(agentId)
    }
  }
}
