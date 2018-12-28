package db

import model.Submission
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
   * Add a submission to the registry
   * @param submission the submission to add
   */
  suspend fun addSubmission(submission: Submission)

  /**
   * Get a single submission from the registry
   * @param submissionId the submission's ID
   * @return the submission or `null` if the submission does not exist
   */
  suspend fun findSubmissionById(submissionId: String): Submission?

  /**
   * Add a process chain to a submission
   * @param processChain the process chain to add
   * @param submissionId the submission ID
   * @param status the status of the process chain
   * @throws NoSuchElementException if there is no submission with the given ID
   */
  suspend fun addProcessChain(processChain: ProcessChain, submissionId: String,
      status: ProcessChainStatus = ProcessChainStatus.REGISTERED)

  /**
   * Find all process chains that belong to a given submission
   * @param submissionId the submission's ID
   * @return the list of process chains (may be empty if the submission does not
   * exist or if it has no process chains)
   */
  suspend fun findProcessChainsBySubmissionId(submissionId: String): List<ProcessChain>

  /**
   * Find all process chains with a given status
   * @param status the status
   * @param limit the maximum number of process chains to return (may be `null`
   * if all process chains should be returned)
   * @return the list of process chains
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
   * Get the status of a process chain
   * @param processChainId the process chain ID
   * @return the process chain status
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus

  /**
   * Set the output of a process chain
   * @param processChainId the process chain ID
   * @param output the output to set (may be `null` if the output should be removed)
   */
  suspend fun setProcessChainOutput(processChainId: String, output: Map<String, List<String>>?)

  /**
   * Get the output of a process chain
   * @param processChainId the process chain ID
   * @return the output (may be `null` if the process chain does not have any
   * output yet)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainOutput(processChainId: String): Map<String, List<String>>?
}
