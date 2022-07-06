package agent

/**
 * Keeps a list of agents
 * @author Michel Kraemer
 */
interface AgentRegistry {
  /**
   * Parameters for [selectCandidates]
   */
  data class SelectCandidatesParam(
      /**
       * The required capabilities for which to find a suitable agent
       */
      val requiredCapabilities: Collection<String>,

      /**
       * The minimum priority of the process chains for which a candidate
       * agent should be selected
       */
      val minPriority: Int,

      /**
       * The maximum priority of the process chains for which a candidate
       * agent should be selected
       */
      val maxPriority: Int,

      /**
       * The total number of the process chains for which a candidate
       * agent should be selected
       */
      val count: Long
  )

  /**
   * For each item in [params], try to find an address of an agent that
   * is able to handle the requiredCapabilities, priorities, and process chain
   * counts specified (see [SelectCandidatesParam]). The method returns a list
   * of required capability sets and agent addresses.
   */
  suspend fun selectCandidates(params: List<SelectCandidatesParam>):
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
