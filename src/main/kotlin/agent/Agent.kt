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
   * @return the process chain results (i.e. a map whose keys are IDs of
   * output arguments and whose values are paths to result files)
   */
  suspend fun execute(processChain: ProcessChain): Map<String, List<String>>
}
