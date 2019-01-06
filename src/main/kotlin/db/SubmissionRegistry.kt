package db

import model.Submission
import model.processchain.ProcessChain

/**
 * A registry for submissions and process chains
 * @author Michel Kraemer
 */
interface SubmissionRegistry {
  /**
   * The status of a process chain
   */
  enum class ProcessChainStatus {
    /**
     * The process chain has been added to the registry
     */
    REGISTERED,

    /**
     * The process chain is currently being executed
     */
    RUNNING,

    /**
     * The process chain was executed successfully
     */
    SUCCESS,

    /**
     * The process chain failed
     */
    ERROR
  }

  /**
   * Add a submission to the registry
   * @param submission the submission to add
   */
  suspend fun addSubmission(submission: Submission)

  /**
   * Get a list of all submissions in the registry
   * @return all submissions
   */
  suspend fun findSubmissions(): Collection<Submission>

  /**
   * Get a single submission from the registry
   * @param submissionId the submission's ID
   * @return the submission or `null` if the submission does not exist
   */
  suspend fun findSubmissionById(submissionId: String): Submission?

  /**
   * Atomically fetch a submission that has the given `currentStatus` and
   * set its status to `newStatus` before returning it.
   * @param currentStatus the current status of the submission
   * @param newStatus the new status
   * @return the submission (or `null` if there was no submission with
   * the given `currentStatus`)
   */
  suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission?

  /**
   * Set the status of a submission
   * @param submissionId the submission ID
   * @param status the new status
   */
  suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status)

  /**
   * Add multiple process chains to a submission
   * @param processChains the process chains to add
   * @param submissionId the submission ID
   * @param status the status of the process chains
   * @throws NoSuchElementException if there is no submission with the given ID
   */
  suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus = ProcessChainStatus.REGISTERED)

  /**
   * Find all process chains that belong to a given submission
   * @param submissionId the submission's ID
   * @return the list of process chains (may be empty if the submission does not
   * exist or if it has no process chains)
   */
  suspend fun findProcessChainsBySubmissionId(submissionId: String): Collection<ProcessChain>

  /**
   * Count the number of process chains that belong to a given submission
   * @param submissionId the submission's ID
   * @return the number of process chains belonging to the given submission
   */
  suspend fun countProcessChainsBySubmissionId(submissionId: String): Long

  /**
   * Count the number of process chains from a certain submission that have a
   * given status
   * @param submissionId the submission's ID
   * @param status the status
   * @return the number of process chains that belong to the given submission
   * and that have the given status
   */
  suspend fun countProcessChainsByStatus(submissionId: String, status: ProcessChainStatus): Long

  /**
   * Atomically fetch a process chain that has the given `currentStatus` and
   * set its status to `newStatus` before returning it.
   * @param currentStatus the current status of the process chain
   * @param newStatus the new status
   * @return the process chain (or `null` if there was no process chain with
   * the given `currentStatus`)
   */
  suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain?

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
