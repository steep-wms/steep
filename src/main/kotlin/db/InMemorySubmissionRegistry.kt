package db

import com.fasterxml.jackson.module.kotlin.readValue
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.getLockAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import model.Submission
import model.processchain.ProcessChain

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
      val processChain: ProcessChain,
      val submissionId: String,
      val status: ProcessChainStatus,
      val results: Map<String, List<String>>? = null,
      val errorMessage: String? = null
  )

  private val submissions: Future<AsyncMap<String, String>>
  private val processChains:  Future<AsyncMap<String, String>>

  init {
    val sharedData = vertx.sharedData()
    submissions = Future.future()
    processChains = Future.future()
    sharedData.getAsyncMap(ASYNC_MAP_SUBMISSIONS, submissions)
    sharedData.getAsyncMap(ASYNC_MAP_PROCESS_CHAINS, processChains)
  }

  override suspend fun addSubmission(submission: Submission) {
    val str = JsonUtils.mapper.writeValueAsString(submission)
    submissions.await().putAwait(submission.id, str)
  }

  override suspend fun findSubmissions(): List<Submission> {
    val map = submissions.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values.map { JsonUtils.mapper.readValue<Submission>(it) }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    return submissions.await().getAwait(submissionId)?.let {
      JsonUtils.mapper.readValue(it)
    }
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_SUBMISSIONS)
    try {
      val map = submissions.await()
      val values = awaitResult<List<String>> { map.values(it) }
      val submission = values.map { JsonUtils.mapper.readValue<Submission>(it) }
          .find { it.status == currentStatus }
      return submission?.also {
        val newSubmission = it.copy(status = newStatus)
        map.putAwait(it.id, JsonUtils.mapper.writeValueAsString(newSubmission))
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
        val entry = JsonUtils.mapper.readValue<Submission>(it)
        val newSubmission = updater(entry)
        map.putAwait(submissionId, JsonUtils.mapper.writeValueAsString(newSubmission))
      }
    } finally {
      lock.release()
    }
  }

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    updateSubmission(submissionId) { it.copy(status = status) }
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
          val e = ProcessChainEntry(processChain, submissionId, status)
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
  }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String) =
      findProcessChainEntries()
          .filter { it.submissionId == submissionId }
          .map { it.processChain }

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    return processChains.await().getAwait(processChainId)?.let {
      JsonUtils.mapper.readValue<ProcessChainEntry>(it).processChain
    }
  }

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

  override suspend fun getProcessChainSubmissionId(processChainId: String): String =
      getProcessChainEntryById(processChainId).submissionId

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateProcessChain(processChainId) { it.copy(status = status) }
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus =
      getProcessChainEntryById(processChainId).status

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<String>>?) {
    updateProcessChain(processChainId) { it.copy(results = results) }
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<String>>? =
      getProcessChainEntryById(processChainId).results

  override suspend fun setProcessChainErrorMessage(processChainId: String,
      errorMessage: String?) {
    updateProcessChain(processChainId) { it.copy(errorMessage = errorMessage) }
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainEntryById(processChainId).errorMessage
}
