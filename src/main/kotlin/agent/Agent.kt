package agent

import model.processchain.ProcessChain

/**
 * An agent executes process chains
 * @author Michel Kraemer
 */
interface Agent {
  /**
   * The agent's unique identifier
   */
  val id: String

  /**
   * Execute the given process chain
   * @param processChain the process chain to execute
   * @param runNumber the number of the process chain [model.processchain.Run]
   * @return the process chain results (i.e. result files grouped by output
   * argument IDs)
   */
  suspend fun execute(processChain: ProcessChain, runNumber: Long): Map<String, List<Any>>
}
