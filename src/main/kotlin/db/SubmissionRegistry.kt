package db

import io.vertx.core.json.JsonObject
import model.Submission
import model.processchain.ProcessChain
import java.time.Instant

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
   * @param size the maximum number of submissions to return (may be negative
   * if all submissions should be returned)
   * @param offset the index of the first submission to return
   * @param order a positive number if submissions should be returned in an
   * ascending order, negative otherwise
   * @return all submissions
   */
  suspend fun findSubmissions(size: Int = -1, offset: Int = 0, order: Int = 1): Collection<Submission>

  /**
   * Get a single submission from the registry
   * @param submissionId the submission's ID
   * @return the submission or `null` if the submission does not exist
   */
  suspend fun findSubmissionById(submissionId: String): Submission?

  /**
   * Get a list of the IDs of submissions that have a given status
   * @param status the status
   * @return the list of submission IDs
   */
  suspend fun findSubmissionIdsByStatus(status: Submission.Status): Collection<String>

  /**
   * Get the number of existing submissions
   * @return the number of submissions
   */
  suspend fun countSubmissions(): Long

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
   * Set the start time of a submission
   * @param submissionId the submission ID
   * @param startTime the new start time
   */
  suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant)

  /**
   * Set the end time of a submission
   * @param submissionId the submission ID
   * @param endTime the new end time
   */
  suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant)

  /**
   * Set the status of a submission
   * @param submissionId the submission ID
   * @param status the new status
   */
  suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status)

  /**
   * Get the status of a submission
   * @param submissionId the submission ID
   * @return the submission status
   * @throws NoSuchElementException if the submission does not exist
   */
  suspend fun getSubmissionStatus(submissionId: String): Submission.Status

  /**
   * Set a submission's execution state
   * @param submissionId the submission ID
   * @param state the state to set or `null` if the state should be removed
   */
  suspend fun setSubmissionExecutionState(submissionId: String, state: JsonObject?)

  /**
   * Get a submission's execution state
   * @param submissionId the submission ID
   * @return the state or `null` if the submission does not have an
   * execution state
   * @throws NoSuchElementException if the submission does not exist
   */
  suspend fun getSubmissionExecutionState(submissionId: String): JsonObject?

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
   * Find all process chains that belong to a given submission and return their
   * IDs and their statuses
   * @param submissionId the submission's ID
   * @return a map of process chain IDs and statuses
   */
  suspend fun findProcessChainStatusesBySubmissionId(submissionId: String):
      Map<String, ProcessChainStatus>

  /**
   * Get a single process chain from the registry
   * @param processChainId the process chain's ID
   * @return the process chain or `null` if the process chain does not exist
   */
  suspend fun findProcessChainById(processChainId: String): ProcessChain?

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
   * Set the start time of a process chain
   * @param processChainId the process chain ID
   * @param startTime the new start time (may be `null` if the start time
   * should be removed)
   */
  suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?)

  /**
   * Get the start time of a process chain
   * @param processChainId the process chain ID
   * @return the start time (may be `null` if the process chain has not
   * started yet)
   */
  suspend fun getProcessChainStartTime(processChainId: String): Instant?

  /**
   * Set the end time of a process chain
   * @param processChainId the submission ID
   * @param endTime the new end time (may be `null` if the end time should be
   * removed)
   */
  suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?)

  /**
   * Get the end time of a process chain
   * @param processChainId the process chain ID
   * @return the end time (may be `null` if the process chain has not
   * finished yet)
   */
  suspend fun getProcessChainEndTime(processChainId: String): Instant?

  /**
   * Get the ID of the submission the given process chain belongs to
   * @param processChainId the process chain ID
   * @return the ID of the submission the process chain belongs to
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainSubmissionId(processChainId: String): String

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
   * Set the results of a process chain
   * @param processChainId the process chain ID
   * @param results the results to set (may be `null` if the results should be removed)
   */
  suspend fun setProcessChainResults(processChainId: String, results: Map<String, List<String>>?)

  /**
   * Get the results of a process chain
   * @param processChainId the process chain ID
   * @return the results (may be `null` if the process chain does not have any
   * result yet)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainResults(processChainId: String): Map<String, List<String>>?

  /**
   * Set the error message for a process chain
   * @param processChainId the process chain ID
   * @param errorMessage the error message (may be `null` if the error message
   * should be removed)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun setProcessChainErrorMessage(processChainId: String, errorMessage: String?)

  /**
   * Get the error message of a process chain
   * @param processChainId the process chain ID
   * @return the error message (may be `null` if the process chain oes not have
   * an error message)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainErrorMessage(processChainId: String): String?
}
