package db

import com.fasterxml.jackson.module.kotlin.readValue
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Future
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
      val submission: Submission
  )

  private val processChainEntryID = AtomicInteger()
  private val submissionEntryID = AtomicInteger()

  private val submissions: Future<AsyncMap<String, String>>
  private val processChains: Future<AsyncMap<String, String>>
  private val executionStates: Future<AsyncMap<String, String>>

  init {
    val sharedData = vertx.sharedData()
    submissions = Future.future()
    processChains = Future.future()
    executionStates = Future.future()
    sharedData.getAsyncMap(ASYNC_MAP_SUBMISSIONS, submissions)
    sharedData.getAsyncMap(ASYNC_MAP_PROCESS_CHAINS, processChains)
    sharedData.getAsyncMap(ASYNC_MAP_EXECUTION_STATE, executionStates)
  }

  override suspend fun close() {
    // nothing to do here
  }

  override suspend fun addSubmission(submission: Submission) {
    val entry = SubmissionEntry(submissionEntryID.getAndIncrement(), submission)
    val str = JsonUtils.mapper.writeValueAsString(entry)
    submissions.await().putAwait(submission.id, str)
  }

  override suspend fun findSubmissions(size: Int, offset: Int, order: Int): List<Submission> {
    val map = submissions.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.mapper.readValue<SubmissionEntry>(it) }
        .sortedBy { it.serial }
        .let { if (order < 0) it.reversed() else it }
        .drop(offset)
        .let { if (size >= 0) it.take(size) else it }
        .map { it.submission }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    return submissions.await().getAwait(submissionId)?.let {
      JsonUtils.mapper.readValue<SubmissionEntry>(it).submission
    }
  }

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status): Collection<String> {
    val map = submissions.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values.map { JsonUtils.mapper.readValue<SubmissionEntry>(it) }
        .filter { it.submission.status == status }
        .sortedBy { it.serial }
        .map { it.submission.id }
  }

  override suspend fun countSubmissions(): Long {
    return submissions.await().sizeAwait().toLong()
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val map = submissions.await()
      val values = awaitResult<List<String>> { map.values(it) }
      val entry = values.map { JsonUtils.mapper.readValue<SubmissionEntry>(it) }
          .find { it.submission.status == currentStatus }
      return entry?.let {
        val newEntry = it.copy(submission = it.submission.copy(status = newStatus))
        map.putAwait(it.submission.id, JsonUtils.mapper.writeValueAsString(newEntry))
        it.submission
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun updateSubmission(submissionId: String,
      updater: (Submission) -> Submission) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val map = submissions.await()
      map.getAwait(submissionId)?.let {
        val oldEntry = JsonUtils.mapper.readValue<SubmissionEntry>(it)
        val newSubmission = updater(oldEntry.submission)
        val newEntry = oldEntry.copy(submission = newSubmission)
        map.putAwait(submissionId, JsonUtils.mapper.writeValueAsString(newEntry))
      }
    } finally {
      lock.release()
    }
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
          map.putAwait(processChain.id, JsonUtils.mapper.writeValueAsString(e))
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
    return values.map { JsonUtils.mapper.readValue<ProcessChainEntry>(it) }
        .sortedBy { it.serial }
  }

  override suspend fun findProcessChains(size: Int, offset: Int, order: Int):
      Collection<Pair<ProcessChain, String>> =
      findProcessChainEntries()
          .let { if (order < 0) it.reversed() else it }
          .drop(offset)
          .let { if (size >= 0) it.take(size) else it }
          .map { Pair(it.processChain, it.submissionId) }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String,
      size: Int, offset: Int, order: Int) =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId }
          .let { if (order < 0) it.reversed() else it }
          .drop(offset)
          .let { if (size >= 0) it.take(size) else it }
          .map { it.processChain }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String) =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId }
          .map { Pair(it.processChain.id, it.status) }
          .toMap()

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    return processChains.await().getAwait(processChainId)?.let {
      JsonUtils.mapper.readValue<ProcessChainEntry>(it).processChain
    }
  }

  override suspend fun countProcessChains(): Long =
      findProcessChainEntries().count().toLong()

  override suspend fun countProcessChainsBySubmissionId(submissionId: String): Long =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId }
          .count().toLong()

  override suspend fun countProcessChainsByStatus(submissionId: String,
      status: ProcessChainStatus): Long =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId }
          .filter { it.status == status }
          .count().toLong()

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
    try {
      val map = processChains.await()
      val values = awaitResult<List<String>> { map.values(it) }
      val entry = values.map { JsonUtils.mapper.readValue<ProcessChainEntry>(it) }
          .find { it.status == currentStatus }
      return entry?.let {
        val newEntry = it.copy(status = newStatus)
        map.putAwait(it.processChain.id, JsonUtils.mapper.writeValueAsString(newEntry))
        it.processChain
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun getProcessChainEntryById(processChainId: String): ProcessChainEntry {
    val map = processChains.await()
    val str = map.getAwait(processChainId) ?: throw NoSuchElementException(
        "There is no process chain with ID `$processChainId'")
    return JsonUtils.mapper.readValue(str)
  }

  private suspend fun updateProcessChain(processChainId: String,
      updater: (ProcessChainEntry) -> ProcessChainEntry) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_PROCESS_CHAINS)
    try {
      val map = processChains.await()
      map.getAwait(processChainId)?.let {
        val entry = JsonUtils.mapper.readValue<ProcessChainEntry>(it)
        val newEntry = updater(entry)
        map.putAwait(processChainId, JsonUtils.mapper.writeValueAsString(newEntry))
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
