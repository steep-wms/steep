package agent

import model.processchain.ProcessChain
import java.util.ArrayDeque
import java.util.Deque

/**
 * A registry for [LocalAgent]s
 * @author Michel Kraemer
 */
class LocalAgentRegistry(nAgents: Int = 1) : AgentRegistry {
  private val agents: Deque<Agent> = ArrayDeque((1..nAgents).map { LocalAgent() })

  override suspend fun allocate(processChain: ProcessChain): Agent? =
      agents.poll()

  override suspend fun deallocate(agent: Agent) {
    agents.offerLast(agent)
  }
}
