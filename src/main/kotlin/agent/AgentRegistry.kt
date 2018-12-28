package agent

import model.processchain.ProcessChain

/**
 * Keeps a list of agents
 * @author Michel Kraemer
 */
interface AgentRegistry {
  /**
   * Allocate an agent to execute a given process chain
   * @param processChain the process chain
   * @return the agent
   */
  suspend fun allocate(processChain: ProcessChain): Agent?

  /**
   * Deallocate an agent
   */
  suspend fun deallocate(agent: Agent)
}
