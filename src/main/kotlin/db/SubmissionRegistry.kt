package db

import model.processchain.ProcessChain

/**
 * A registry for submissions and process chains
 * @author Michel Kraemer
 */
interface SubmissionRegistry {
  enum class ProcessChainStatus {
    REGISTERED,
    RUNNING,
    SUCCESS,
    ERROR
  }

  /**
   * Find all process chains with a given status
   * @param status the status
   * @param limit the maximum number of process chains to return (may be `null`
   * if all process chains should be returned)
   */
  suspend fun findProcessChainsByStatus(status: ProcessChainStatus,
      limit: Int? = null): List<ProcessChain>

  /**
   * Set the status of a process chain
   * @param processChainId the process chain ID
   * @param status the new status
   */
  suspend fun setProcessChainStatus(processChainId: String, status: ProcessChainStatus)

  /**
   * Set the output of a process chain
   * @param processChainId the process chain ID
   * @param output the output to set
   */
  suspend fun setProcessChainOutput(processChainId: String, output: List<String>)
}
