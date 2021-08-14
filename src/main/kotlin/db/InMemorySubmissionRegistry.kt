package db

import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.getLockAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.removeAwait
import io.vertx.kotlin.core.shareddata.sizeAwait
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import model.Submission
import model.processchain.ProcessChain
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A submission registry that keeps objects in memory
 * @param vertx the current Vert.x instance
 * @author Michel Kraemer
 */
class InMemorySubmissionRegistry(private val vertx: Vertx) : SubmissionRegistry {
  companion object {
    /**
     * Name of a cluster-wide map keeping [Submission]s
     */
    private const val ASYNC_MAP_SUBMISSIONS = "InMemorySubmissionRegistry.Submissions"

    /**
     * Name of a cluster-wide map keeping [ProcessChain]s
     */
    private const val ASYNC_MAP_PROCESS_CHAINS = "InMemorySubmissionRegistry.ProcessChains"

    /**
     * Name of a cluster-wide map keeping execution states
     */
    private const val ASYNC_MAP_EXECUTION_STATE = "InMemorySubmissionRegistry.ExecutionStates"

    /**
     * Name of a cluster-wide lock used to make atomic operations on the
     * cluster-wide map of submissions
     */
    private const val LOCK_SUBMISSIONS = "InMemorySubmissionRegistry.Submissions.Lock"

    /**
     * Name of a cluster-wide lock used to make atomic operations on the
     * cluster-wide map of process chains
     */
    private const val LOCK_PROCESS_CHAINS = "InMemorySubmissionRegistry.ProcessChains.Lock"
  }

  private data class ProcessChainEntry(
      val serial: Int,
      val processChain: ProcessChain,
      val submissionId: String,
      val status: ProcessChainStatus,
      val startTime: Instant? = null,
      val endTime: Instant? = null,
      val results: Map<String, List<Any>>? = null,
      val errorMessage: String? = null
  )

  private data class SubmissionEntry(
      val serial: Int,
      val submission: Submission,
      val results: Map<String, List<Any>>? = null,
      val errorMessage: String? = null
  )

  private val processChainEntryID = AtomicInteger()
  private val submissionEntryID = AtomicInteger()

  private val submissions: Future<AsyncMap<String, String>>
  private val processChains: Future<AsyncMap<String, String>>
  private val executionStates: Future<AsyncMap<String, String>>

  init {
    val sharedData = vertx.sharedData()
    val submissionsPromise = Promise.promise<AsyncMap<String, String>>()
    val processChainsPromise = Promise.promise<AsyncMap<String, String>>()
    val executionStatesPromise = Promise.promise<AsyncMap<String, String>>()
    sharedData.getAsyncMap(ASYNC_MAP_SUBMISSIONS, submissionsPromise)
    sharedData.getAsyncMap(ASYNC_MAP_PROCESS_CHAINS, processChainsPromise)
    sharedData.getAsyncMap(ASYNC_MAP_EXECUTION_STATE, executionStatesPromise)
    submissions = submissionsPromise.future()
    processChains = processChainsPromise.future()
    executionStates = executionStatesPromise.future()
  }

  override suspend fun close() {
    // nothing to do here
  }

  override suspend fun addSubmission(submission: Submission) {
    val entry = SubmissionEntry(submissionEntryID.getAndIncrement(), submission)
    val str = JsonUtils.writeValueAsString(entry)
    submissions.await().putAwait(submission.id, str)
  }

  override suspend fun findSubmissions(status: Submission.Status?, size: Int,
      offset: Int, order: Int): List<Submission> {
    val map = submissions.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.readValue<SubmissionEntry>(it) }
        .filter { status == null || it.submission.status == status }
        .sortedBy { it.serial }
        .let { if (order < 0) it.reversed() else it }
        .drop(offset)
        .let { if (size >= 0) it.take(size) else it }
        .map { it.submission }
  }

  private suspend fun findSubmissionEntryById(submissionId: String): SubmissionEntry? {
    return submissions.await().getAwait(submissionId)?.let {
      JsonUtils.readValue<SubmissionEntry>(it)
    }
  }

  override suspend fun findSubmissionById(submissionId: String) =
      findSubmissionEntryById(submissionId)?.submission

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status): Collection<String> {
    val map = submissions.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values.map { JsonUtils.readValue<SubmissionEntry>(it) }
        .filter { it.submission.status == status }
        .sortedBy { it.serial }
        .map { it.submission.id }
  }

  override suspend fun countSubmissions(status: Submission.Status?): Long {
    val map = submissions.await()
    return if (status == null) {
      map.sizeAwait().toLong()
    } else {
      val values = awaitResult<List<String>> { map.values(it) }
      values.map { JsonUtils.readValue<SubmissionEntry>(it) }
          .filter { it.submission.status == status }
          .size.toLong()
    }
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val map = submissions.await()
      val values = awaitResult<List<String>> { map.values(it) }
      val entry = values.map { JsonUtils.readValue<SubmissionEntry>(it) }
          .find { it.submission.status == currentStatus }
      return entry?.let {
        val newEntry = it.copy(submission = it.submission.copy(status = newStatus))
        map.putAwait(it.submission.id, JsonUtils.writeValueAsString(newEntry))
        it.submission
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun updateSubmissionEntry(submissionId: String,
      updater: (SubmissionEntry) -> SubmissionEntry) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val map = submissions.await()
      map.getAwait(submissionId)?.let {
        val oldEntry = JsonUtils.readValue<SubmissionEntry>(it)
        val newEntry = updater(oldEntry)
        map.putAwait(submissionId, JsonUtils.writeValueAsString(newEntry))
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun updateSubmission(submissionId: String,
      updater: (Submission) -> Submission) {
    updateSubmissionEntry(submissionId) { it.copy(submission = updater(it.submission)) }
  }

  override suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant) {
    updateSubmission(submissionId) { it.copy(startTime = startTime) }
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    updateSubmission(submissionId) { it.copy(endTime = endTime) }
  }

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    updateSubmission(submissionId) { it.copy(status = status) }
  }

  override suspend fun getSubmissionStatus(submissionId: String): Submission.Status {
    val s = findSubmissionById(submissionId) ?: throw NoSuchElementException(
        "There is no submission with ID `$submissionId'")
    return s.status
  }

  override suspend fun setSubmissionResults(submissionId: String, results: Map<String, List<Any>>?) {
    updateSubmissionEntry(submissionId) { it.copy(results = results) }
  }

  override suspend fun getSubmissionResults(submissionId: String): Map<String, List<Any>>? {
    val s = findSubmissionEntryById(submissionId) ?: throw NoSuchElementException(
        "There is no submission with ID `$submissionId'")
    return s.results
  }

  override suspend fun setSubmissionErrorMessage(submissionId: String,
      errorMessage: String?) {
    updateSubmissionEntry(submissionId) { it.copy(errorMessage = errorMessage) }
  }

  override suspend fun getSubmissionErrorMessage(submissionId: String): String? {
    val s = findSubmissionEntryById(submissionId) ?: throw NoSuchElementException(
        "There is no submission with ID `$submissionId'")
    return s.errorMessage
  }

  override suspend fun setSubmissionExecutionState(submissionId: String,
      state: JsonObject?) {
    submissions.await().getAwait(submissionId) ?: return
    if (state == null) {
      executionStates.await().removeAwait(submissionId)
    } else {
      executionStates.await().putAwait(submissionId, state.encode())
    }
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? {
    submissions.await().getAwait(submissionId) ?: throw NoSuchElementException(
        "There is no submission with ID `$submissionId'")
    return executionStates.await().getAwait(submissionId)?.let { JsonObject(it) }
  }

  override suspend fun deleteSubmissionsFinishedBefore(timestamp: Instant): Collection<String> {
    val sharedData = vertx.sharedData()
    val submissionLock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val processChainLock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
      try {
        // find IDs of submissions to delete
        val submissionMap = submissions.await()
        val submissionValues = awaitResult<List<String>> { submissionMap.values(it) }
        val submissionIDs = submissionValues
            .map { JsonUtils.readValue<SubmissionEntry>(it) }
            .filter { it.submission.endTime?.isBefore(timestamp) ?: false }
            .map { it.submission.id }
            .toSet()

        // find IDs of all process chains that belong to these submissions
        val processChainMap = this.processChains.await()
        val processChainValues = awaitResult<List<String>> { processChainMap.values(it) }
        val processChainIDs = processChainValues
            .map { JsonUtils.readValue<ProcessChainEntry>(it) }
            .filter { submissionIDs.contains(it.submissionId) }
            .map { it.processChain.id }

        // delete process chains and then submissions
        processChainIDs.forEach { processChainMap.removeAwait(it) }
        submissionIDs.forEach { submissionMap.removeAwait(it) }

        return submissionIDs
      } finally {
        processChainLock.release()
      }
    } finally {
      submissionLock.release()
    }
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    val sharedData = vertx.sharedData()
    val submissionLock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val processChainLock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
      try {
        if (submissions.await().getAwait(submissionId) == null) {
          throw NoSuchElementException("There is no submission with ID `$submissionId'")
        }
        val map = this.processChains.await()
        for (processChain in processChains) {
          val e = ProcessChainEntry(processChainEntryID.getAndIncrement(),
              processChain, submissionId, status)
          map.putAwait(processChain.id, JsonUtils.writeValueAsString(e))
        }
      } finally {
        processChainLock.release()
      }
    } finally {
      submissionLock.release()
    }
  }

  private suspend fun findProcessChainEntries(): List<ProcessChainEntry> {
    val map = processChains.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
        .sortedBy { it.serial }
  }

  override suspend fun findProcessChains(submissionId: String?,
      status: ProcessChainStatus?, size: Int, offset: Int, order: Int) =
      findProcessChainEntries()
          .filter {
            (submissionId == null || it.submissionId == submissionId) &&
                (status == null || it.status == status)
          }
          .let { if (order < 0) it.reversed() else it }
          .drop(offset)
          .let { if (size >= 0) it.take(size) else it }
          .map { Pair(it.processChain, it.submissionId) }

  override suspend fun findProcessChainIdsByStatus(status: ProcessChainStatus) =
      findProcessChainEntries()
          .filter { it.status == status }
          .map { it.processChain.id }

  override suspend fun findProcessChainIdsBySubmissionIdAndStatus(
      submissionId: String, status: ProcessChainStatus) =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId && it.status == status }
          .map { it.processChain.id }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String) =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId }
          .associate { Pair(it.processChain.id, it.status) }

  override suspend fun findProcessChainRequiredCapabilities(status: ProcessChainStatus) =
      findProcessChainEntries()
          .filter { it.status == status }
          .map { it.processChain.requiredCapabilities }
          .distinct()

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    return processChains.await().getAwait(processChainId)?.let {
      JsonUtils.readValue<ProcessChainEntry>(it).processChain
    }
  }

  override suspend fun countProcessChains(submissionId: String?,
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?): Long =
      findProcessChainEntries()
          .count {
            (submissionId == null || it.submissionId == submissionId) &&
                (status == null || it.status == status) &&
                (requiredCapabilities == null ||
                    (it.processChain.requiredCapabilities.size == requiredCapabilities.size &&
                        it.processChain.requiredCapabilities.containsAll(requiredCapabilities)))
          }
          .toLong()

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?): ProcessChain? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
    try {
      val map = processChains.await()
      val values = awaitResult<List<String>> { map.values(it) }
      val entry = values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .filter { it.status == currentStatus && (requiredCapabilities == null ||
              (it.processChain.requiredCapabilities.size == requiredCapabilities.size &&
                  it.processChain.requiredCapabilities.containsAll(requiredCapabilities))) }
          .minByOrNull { it.serial }
      return entry?.let {
        val newEntry = it.copy(status = newStatus)
        map.putAwait(it.processChain.id, JsonUtils.writeValueAsString(newEntry))
        it.processChain
      }
    } finally {
      lock.release()
    }
  }

  override suspend fun existsProcessChain(currentStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>?): Boolean {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
    try {
      val map = processChains.await()
      val values = awaitResult<List<String>> { map.values(it) }
      return values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .any { it.status == currentStatus && (requiredCapabilities == null ||
              (it.processChain.requiredCapabilities.size == requiredCapabilities.size &&
                  it.processChain.requiredCapabilities.containsAll(requiredCapabilities))) }
    } finally {
      lock.release()
    }
  }

  private suspend fun getProcessChainEntryById(processChainId: String): ProcessChainEntry {
    val map = processChains.await()
    val str = map.getAwait(processChainId) ?: throw NoSuchElementException(
        "There is no process chain with ID `$processChainId'")
    return JsonUtils.readValue(str)
  }

  private suspend fun updateProcessChain(processChainId: String,
      updater: (ProcessChainEntry) -> ProcessChainEntry) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
    try {
      val map = processChains.await()
      map.getAwait(processChainId)?.let {
        val entry = JsonUtils.readValue<ProcessChainEntry>(it)
        val newEntry = updater(entry)
        map.putAwait(processChainId, JsonUtils.writeValueAsString(newEntry))
      }
    } finally {
      lock.release()
    }
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    updateProcessChain(processChainId) { it.copy(startTime = startTime) }
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainEntryById(processChainId).startTime

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateProcessChain(processChainId) { it.copy(endTime = endTime) }
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainEntryById(processChainId).endTime

  override suspend fun getProcessChainSubmissionId(processChainId: String): String =
      getProcessChainEntryById(processChainId).submissionId

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateProcessChain(processChainId) { it.copy(status = status) }
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    updateProcessChain(processChainId) {
      if (it.status == currentStatus) {
        it.copy(status = newStatus)
      } else {
        it
      }
    }
  }

  override suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
    try {
      val map = processChains.await()
      val values = awaitResult<List<String>> { map.values(it) }
      values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .filter { it.submissionId == submissionId && it.status == currentStatus }
          .forEach { entry ->
            val newEntry = entry.copy(status = newStatus)
            map.putAwait(entry.processChain.id,
                JsonUtils.writeValueAsString(newEntry))
          }
    } finally {
      lock.release()
    }
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus =
      getProcessChainEntryById(processChainId).status

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    updateProcessChain(processChainId) { it.copy(results = results) }
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? =
      getProcessChainEntryById(processChainId).results

  override suspend fun setProcessChainErrorMessage(processChainId: String,
      errorMessage: String?) {
    updateProcessChain(processChainId) { it.copy(errorMessage = errorMessage) }
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainEntryById(processChainId).errorMessage
}
