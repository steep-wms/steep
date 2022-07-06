package db

import io.vertx.core.json.JsonObject
import model.Submission
import model.processchain.ProcessChain
import search.Query
import search.SearchResult
import search.Type
import java.time.Instant

/**
 * A registry for submissions and process chains
 * @author Michel Kraemer
 */
interface SubmissionRegistry : Registry {
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
     * The process chain's execution has been cancelled
     */
    CANCELLED,

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
   * Get a list of all raw (JSON) submissions in the registry
   * @param status an optional status the returned submissions should have
   * @param size the maximum number of submissions to return (may be negative
   * if all submissions should be returned)
   * @param offset the index of the first submission to return
   * @param order a positive number if the submissions should be returned in
   * ascending order, negative otherwise
   * @param excludeWorkflows `true` if all workflows should be excluded from
   * the result
   * @param excludeSources `true` if the all workflow sources should be excluded
   * from the result
   * @return all submissions
   */
  suspend fun findSubmissionsRaw(status: Submission.Status? = null, size: Int = -1,
      offset: Int = 0, order: Int = 1, excludeWorkflows: Boolean = false,
      excludeSources: Boolean = false): Collection<JsonObject>

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
   * @param status an optional status the submissions should have
   * @return the number of submissions
   */
  suspend fun countSubmissions(status: Submission.Status? = null): Long

  /**
   * Atomically fetch a submission that has the given `currentStatus` and
   * set its status to `newStatus` before returning it. Returned submissions
   * should be ordered by their workflow priority. Submissions with the same
   * priority should be returned in the order in which they have been added to
   * the registry.
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
   * Sets the [priority] of the submission with the given [submissionId]
   * if (and only if) the submission has not already finished (i.e. only if its
   * status is either [Submission.Status.ACCEPTED] or [Submission.Status.RUNNING]).
   * Priorities of finished submissions are fixed and cannot be modified.
   * Return `true` if the submission was updated, `false` otherwise.
   *
   * Attention: This method does change the priorities of the process chains
   * belonging to this submission. Use [setAllProcessChainsPriority] for this.
   */
  suspend fun setSubmissionPriority(submissionId: String, priority: Int): Boolean

  /**
   * Set the results of a submission
   * @param submissionId the submission ID
   * @param results the results to set (may be `null` if the results should be removed)
   */
  suspend fun setSubmissionResults(submissionId: String, results: Map<String, List<Any>>?)

  /**
   * Get the results of a submission
   * @param submissionId the submission ID
   * @return the results (may be `null` if the submission does not have any
   * result yet)
   * @throws NoSuchElementException if the submission does not exist
   */
  suspend fun getSubmissionResults(submissionId: String): Map<String, List<Any>>?

  /**
   * Set the error message of a submission
   * @param submissionId the submission ID
   * @param errorMessage the error message (may be `null` if the error message
   * should be removed)
   */
  suspend fun setSubmissionErrorMessage(submissionId: String, errorMessage: String?)

  /**
   * Get the error message of a submission
   * @param submissionId the process chain ID
   * @return the error message (may be `null` if the submission does not have
   * an error message)
   * @throws NoSuchElementException if the submission does not exist
   */
  suspend fun getSubmissionErrorMessage(submissionId: String): String?

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
   * Delete all submissions that have finished before the given [timestamp]
   * (regardless of their status) and return their IDs
   */
  suspend fun deleteSubmissionsFinishedBefore(timestamp: Instant): Collection<String>

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
   * Get a list of all process chains in the registry
   * @param submissionId an optional ID of a submission the process chains
   * should belong to
   * @param status an optional status the returned process chains should have
   * @param size the maximum number of process chains to return (may be negative
   * if all process chains should be returned)
   * @param offset the index of the first process chain to return
   * @param order a positive number if the process chains should be returned in
   * an ascending order, negative otherwise
   * @param excludeExecutables `true` if all executables should be excluded
   * from the result
   * @return all process chains and the ID of their respective submission
   */
  suspend fun findProcessChains(submissionId: String? = null,
      status: ProcessChainStatus? = null, size: Int = -1, offset: Int = 0,
      order: Int = 1, excludeExecutables: Boolean = false): Collection<Pair<ProcessChain, String>>

  /**
   * Find the IDs of all process chains that have a given status
   * @param status the expected process chain status
   * @return the list of process chain IDs (may be empty if there are no
   * process chains with the given status)
   */
  suspend fun findProcessChainIdsByStatus(status: ProcessChainStatus): Collection<String>

  /**
   * Find the IDs of all process chains that belong to a given submission
   * and have the given status
   * @param submissionId the submission's ID
   * @param status the expected process chain status
   * @return the list of process chain IDs (may be empty if the submission does
   * not exist or if it has no process chains with the given status)
   */
  suspend fun findProcessChainIdsBySubmissionIdAndStatus(submissionId: String,
      status: ProcessChainStatus): Collection<String>

  /**
   * Find all process chains that belong to a given submission and return their
   * IDs and their statuses
   * @param submissionId the submission's ID
   * @return a map of process chain IDs and statuses
   */
  suspend fun findProcessChainStatusesBySubmissionId(submissionId: String):
      Map<String, ProcessChainStatus>

  /**
   * Get a list of distinct required capabilities of all process chains with
   * the given [status]. The list consists of pairs. Each pair contains a
   * required capability set and a range specifying the minimum and maximum
   * priority of the process chains found for this set.
   */
  suspend fun findProcessChainRequiredCapabilities(status: ProcessChainStatus):
      List<Pair<Collection<String>, IntRange>>

  /**
   * Get a single process chain from the registry
   * @param processChainId the process chain's ID
   * @return the process chain or `null` if the process chain does not exist
   */
  suspend fun findProcessChainById(processChainId: String): ProcessChain?

  /**
   * Count the number of registered process chains
   * @param submissionId an optional submission ID the process chains should
   * belong to
   * @param status an optional status the process chains should have
   * @param requiredCapabilities an optional set of required capabilities. Only
   * process chains with these required capabilities will be counted.
   * @param minPriority an optional minimum priority. Only process chains that
   * have a priority greater than or equal to `minPriority` will be counted.
   * @return the number of process chains in the registry
   */
  suspend fun countProcessChains(submissionId: String? = null,
      status: ProcessChainStatus? = null,
      requiredCapabilities: Collection<String>? = null,
      minPriority: Int? = null): Long

  /**
   * Group process chains belonging to a given [submissionId] by status and
   * count the items in each group. Example: if the submission contains 1
   * process chain with the status [ProcessChainStatus.RUNNING] and 2 chains
   * with the status [ProcessChainStatus.SUCCESS], the method will return
   * `mapOf(ProcessChainStatus.RUNNING to 1L, ProcessChainStatus.SUCCESS to 2L)`.
   * [submissionId] may be null if all process chains in the registry should
   * be counted.
   */
  suspend fun countProcessChainsPerStatus(submissionId: String? = null):
      Map<ProcessChainStatus, Long>

  /**
   * Atomically fetch a process chain that has the given [currentStatus] and
   * set its status to [newStatus] before returning it. Returned process chains
   * should be ordered by priority. Process chains with the same priority should
   * be returned in the order in which they have been added to the registry.
   * The method only looks for process chains whose set of [requiredCapabilities]
   * equals the given one and whose priority is greater than or equal to [minPriority].
   * If [requiredCapabilities] and [minPriority] have not been specified, the
   * method returns the first process chain found. If no matching process chain
   * was found, the method returns `null`.
   */
  suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>? = null,
      minPriority: Int? = null): ProcessChain?

  /**
   * Check if there is a process chain that has the given [currentStatus] and
   * the optional set of [requiredCapabilities].
   * @param currentStatus the current status of the process chain
   * @param requiredCapabilities an optional set of required capabilities used
   * to narrow down the search
   * @return `true` if there is such a process chain, `false` otherwise
   */
  suspend fun existsProcessChain(currentStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>? = null): Boolean

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
   * Atomically set the status of a process chain if its current status
   * matches a specified one. If it does not match, the method does nothing.
   * @param processChainId the process chain ID
   * @param currentStatus the expected current status
   * @param newStatus the new status
   */
  suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus)

  /**
   * Find all process chains that belong to the submission with the given ID
   * and that have the [currentStatus]. Set their status to [newStatus].
   * @param submissionId the ID of the submission whose process chains to update
   * @param currentStatus the current status of the process chains to update
   * @param newStatus the new status
   */
  suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus)

  /**
   * Get the status of a process chain
   * @param processChainId the process chain ID
   * @return the process chain status
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus

  /**
   * Set the [priority] of the process chain with the given [processChainId]
   * if (and only if) the process chain has not already finished (i.e. only if
   * its status is either [ProcessChainStatus.REGISTERED] or
   * [ProcessChainStatus.RUNNING]). Priorities of finished process chains are
   * fixed and cannot be modified. Return `true` if the process chain was
   * updated, `false` otherwise.
   */
  suspend fun setProcessChainPriority(processChainId: String, priority: Int): Boolean

  /**
   * Set the [priority] of all process chains of the submission with the
   * given [submissionId]. Only affects process chains that are not finished
   * yet (i.e. only those that have a status of either [ProcessChainStatus.REGISTERED]
   * or [ProcessChainStatus.RUNNING]). Priorities of finished process chains are
   * fixed and cannot be modified. They will be skipped silently.
   */
  suspend fun setAllProcessChainsPriority(submissionId: String, priority: Int)

  /**
   * Set the results of a process chain
   * @param processChainId the process chain ID
   * @param results the results to set (may be `null` if the results should be removed)
   */
  suspend fun setProcessChainResults(processChainId: String, results: Map<String, List<Any>>?)

  /**
   * Get the results of a process chain
   * @param processChainId the process chain ID
   * @return the results (may be `null` if the process chain does not have any
   * result yet)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>?

  /**
   * Query the process chains with the given [processChainIds] and return
   * status and results of those that are finished (i.e. not
   * [ProcessChainStatus.REGISTERED] or [ProcessChainStatus.RUNNING])
   */
  suspend fun getProcessChainStatusAndResultsIfFinished(processChainIds: Collection<String>):
      Map<String, Pair<ProcessChainStatus, Map<String, List<Any>>?>>

  /**
   * Set the error message of a process chain
   * @param processChainId the process chain ID
   * @param errorMessage the error message (may be `null` if the error message
   * should be removed)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun setProcessChainErrorMessage(processChainId: String, errorMessage: String?)

  /**
   * Get the error message of a process chain
   * @param processChainId the process chain ID
   * @return the error message (may be `null` if the process chain does not have
   * an error message)
   * @throws NoSuchElementException if the process chain does not exist
   */
  suspend fun getProcessChainErrorMessage(processChainId: String): String?

  /**
   * Searches the registry to find objects that match the given [query].
   * A maximum number of [size] objects beginning from [offset] will be
   * returned in ascending or descending [order].
   */
  suspend fun search(query: Query, size: Int = -1, offset: Int = 0,
      order: Int = 1): Collection<SearchResult>

  /**
   * Count all objects that match the given [query] and are of the given [type].
   * Provides a quick/rough [estimate] or an exact count.
   */
  suspend fun searchCount(query: Query, type: Type, estimate: Boolean): Long
}
