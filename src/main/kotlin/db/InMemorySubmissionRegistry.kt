package db

import com.fasterxml.jackson.module.kotlin.readValue
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import model.Submission
import model.processchain.ProcessChain

/**
 * A submission registry that keeps objects in memory
 * @author Michel Kraemer
 */
class InMemorySubmissionRegistry(vertx: Vertx) : SubmissionRegistry {
  companion object {
    /**
     * Name of a cluster-wide map keeping [Submission]s
     */
    private const val ASYNC_MAP_SUBMISSIONS = "InMemorySubmissionRegistry.Submissions"

    /**
     * Name of a cluster-wide map keeping [ProcessChain]s
     */
    private const val ASYNC_MAP_PROCESS_CHAINS = "InMemorySubmissionRegistry.ProcessChains"
  }

  private data class ProcessChainEntry(
      val processChain: ProcessChain,
      val submissionId: String,
      val status: ProcessChainStatus,
      val output: Map<String, List<String>>? = null
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

  override suspend fun findSubmissionsByStatus(status: Submission.Status, limit: Int?) =
      findSubmissions()
          .filter { it.status == status }
          .take(limit ?: Integer.MAX_VALUE)

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    val map = submissions.await()
    map.getAwait(submissionId)?.let {
      val submission = JsonUtils.mapper.readValue<Submission>(it)
      val newSubmission = submission.copy(status = status)
      map.putAwait(submissionId, JsonUtils.mapper.writeValueAsString(newSubmission))
    }
  }

  override suspend fun addProcessChain(processChain: ProcessChain,
      submissionId: String, status: ProcessChainStatus) {
    if (submissions.await().getAwait(submissionId) == null) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }
    val e = ProcessChainEntry(processChain, submissionId, status)
    processChains.await().putAwait(processChain.id, JsonUtils.mapper.writeValueAsString(e))
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

  override suspend fun findProcessChainsByStatus(status: ProcessChainStatus, limit: Int?) =
      findProcessChainEntries()
          .filter { it.status == status }
          .take(limit ?: Integer.MAX_VALUE)
          .map { it.processChain }

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    val map = processChains.await()
    map.getAwait(processChainId)?.let {
      val entry = JsonUtils.mapper.readValue<ProcessChainEntry>(it)
      val newEntry = entry.copy(status = status)
      map.putAwait(processChainId, JsonUtils.mapper.writeValueAsString(newEntry))
    }
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus {
    val map = processChains.await()
    val str = map.getAwait(processChainId) ?: throw NoSuchElementException(
        "There is no process chain with ID `$processChainId'")
    return JsonUtils.mapper.readValue<ProcessChainEntry>(str).status
  }

  override suspend fun setProcessChainOutput(processChainId: String,
      output: Map<String, List<String>>?) {
    val map = processChains.await()
    map.getAwait(processChainId)?.let {
      val entry = JsonUtils.mapper.readValue<ProcessChainEntry>(it)
      val newEntry = entry.copy(output = output)
      map.putAwait(processChainId, JsonUtils.mapper.writeValueAsString(newEntry))
    }
  }

  override suspend fun getProcessChainOutput(processChainId: String): Map<String, List<String>>? {
    val map = processChains.await()
    val str = map.getAwait(processChainId) ?: throw NoSuchElementException(
        "There is no process chain with ID `$processChainId'")
    return JsonUtils.mapper.readValue<ProcessChainEntry>(str).output
  }
}
