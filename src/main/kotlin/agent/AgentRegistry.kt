package agent

/**
 * Keeps a list of agents
 * @author Michel Kraemer
 */
interface AgentRegistry {
  /**
   * For each given set of [requiredCapabilities] (and respective number of
   * process chains requiring them), try to find an address of an agent that
   * is able to handle them
   */
  suspend fun selectCandidates(requiredCapabilities: List<Pair<Collection<String>, Long>>):
      List<Pair<Collection<String>, String>>

  /**
   * Get a list of registered agents
   */
  suspend fun getAgentIds(): Set<String>

  /**
   * Same as [getAgentIds] but filtered by primary agents (i.e. the first agent
   * in every Steep instance)
   */
  suspend fun getPrimaryAgentIds(): Set<String>

  /**
   * Try to allocate the agent with the given [address] and ask it if it's
   * available for executing the process chain with the given [processChainId].
   * The method returns the allocated agent or `null` if the agent rejected the
   * allocation request or was not reachable at all.
   */
  suspend fun tryAllocate(address: String, processChainId: String): Agent?

  /**
   * Deallocate an agent
   */
  suspend fun deallocate(agent: Agent)
}
