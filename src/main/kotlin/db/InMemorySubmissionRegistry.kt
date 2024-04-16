package db

import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.coAwait
import model.Submission
import model.processchain.ProcessChain
import model.processchain.Run
import search.Locator
import search.Match
import search.Query
import search.SearchResult
import search.SearchResultMatcher
import search.Type
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
      val runs: List<Run> = emptyList(),
      val results: Map<String, List<Any>>? = null
  )

  private data class SubmissionEntry(
      val serial: Int,
      val submission: Submission,
      val results: Map<String, List<Any>>? = null,
      val errorMessage: String? = null
  )

  private data class InternalSearchResult(
      val serial: Int,
      val result: SearchResult,
      val matches: List<Match>
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
    submissions.coAwait().put(submission.id, str).coAwait()
  }

  override suspend fun findSubmissionsRaw(status: Submission.Status?, size: Int,
      offset: Int, order: Int, excludeWorkflows: Boolean,
      excludeSources: Boolean): List<JsonObject> {
    val map = submissions.coAwait()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.readValue<SubmissionEntry>(it) }
        .filter { status == null || it.submission.status == status }
        .sortedBy { it.serial }
        .let { if (order < 0) it.reversed() else it }
        .drop(offset)
        .let { if (size >= 0) it.take(size) else it }
        .map { entry ->
          val r = JsonUtils.toJson(entry.submission)
          if (excludeWorkflows) {
            r.remove("workflow")
          }
          if (excludeSources) {
            r.remove("source")
          }
          r
        }
  }

  private suspend fun findSubmissionEntryById(submissionId: String): SubmissionEntry? {
    return submissions.coAwait().get(submissionId).coAwait()?.let {
      JsonUtils.readValue<SubmissionEntry>(it)
    }
  }

  override suspend fun findSubmissionById(submissionId: String) =
      findSubmissionEntryById(submissionId)?.submission

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status): Collection<String> {
    val map = submissions.coAwait()
    val values = awaitResult<List<String>> { map.values(it) }
    return values.map { JsonUtils.readValue<SubmissionEntry>(it) }
        .filter { it.submission.status == status }
        .sortedBy { it.serial }
        .map { it.submission.id }
  }

  override suspend fun countSubmissions(status: Submission.Status?): Long {
    val map = submissions.coAwait()
    return if (status == null) {
      map.size().coAwait().toLong()
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
    val lock = sharedData.getLock(LOCK_SUBMISSIONS).coAwait()
    try {
      val map = submissions.coAwait()
      val values = awaitResult<List<String>> { map.values(it) }
      val entry = values.map { JsonUtils.readValue<SubmissionEntry>(it) }
          .filter { it.submission.status == currentStatus }
          .sortedWith(compareByDescending<SubmissionEntry> { it.submission.workflow.priority }
              .thenBy { it.serial })
          .firstOrNull()
      return entry?.let {
        val newEntry = it.copy(submission = it.submission.copy(status = newStatus))
        map.put(it.submission.id, JsonUtils.writeValueAsString(newEntry)).coAwait()
        it.submission
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun updateSubmissionEntry(submissionId: String,
      updater: (SubmissionEntry) -> SubmissionEntry) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_SUBMISSIONS).coAwait()
    try {
      val map = submissions.coAwait()
      map.get(submissionId).coAwait()?.let {
        val oldEntry = JsonUtils.readValue<SubmissionEntry>(it)
        val newEntry = updater(oldEntry)
        map.put(submissionId, JsonUtils.writeValueAsString(newEntry)).coAwait()
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

  override suspend fun setSubmissionPriority(submissionId: String, priority: Int): Boolean {
    var updated = false
    updateSubmission(submissionId) { s ->
      if (s.priority != priority && (s.status == Submission.Status.ACCEPTED ||
              s.status == Submission.Status.RUNNING)) {
        updated = true
        s.copy(priority = priority)
      } else {
        s
      }
    }
    return updated
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
    submissions.coAwait().get(submissionId).coAwait() ?: return
    if (state == null) {
      executionStates.coAwait().remove(submissionId).coAwait()
    } else {
      executionStates.coAwait().put(submissionId, state.encode()).coAwait()
    }
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? {
    submissions.coAwait().get(submissionId).coAwait() ?: throw NoSuchElementException(
        "There is no submission with ID `$submissionId'")
    return executionStates.coAwait().get(submissionId).coAwait()?.let { JsonObject(it) }
  }

  override suspend fun deleteSubmissionsFinishedBefore(timestamp: Instant): Collection<String> {
    val sharedData = vertx.sharedData()
    val submissionLock = sharedData.getLock(LOCK_SUBMISSIONS).coAwait()
    try {
      val processChainLock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
      try {
        // find IDs of submissions whose end time is before the given timestamp
        val submissionMap = submissions.coAwait()
        val submissionValues = awaitResult<List<String>> { submissionMap.values(it) }
        val submissionIDs1 = submissionValues
            .map { JsonUtils.readValue<SubmissionEntry>(it) }
            .filter { it.submission.endTime?.isBefore(timestamp) ?: false }
            .map { it.submission.id }
            .toSet()

        // find IDs of finished submissions that do not have an endTime but
        // whose ID was created before the given timestamp (this will also
        // include submissions without a startTime)
        val submissionIDs2 = submissionValues
          .map { JsonUtils.readValue<SubmissionEntry>(it) }
          .filter { it.submission.status != Submission.Status.ACCEPTED &&
              it.submission.status != Submission.Status.RUNNING &&
              it.submission.endTime == null &&
              Instant.ofEpochMilli(UniqueID.toMillis(it.submission.id)).isBefore(timestamp) }
          .map { it.submission.id }
          .toSet()

        val submissionIDs = submissionIDs1 + submissionIDs2

        // find IDs of all process chains that belong to these submissions
        val processChainMap = this.processChains.coAwait()
        val processChainValues = awaitResult<List<String>> { processChainMap.values(it) }
        val processChainIDs = processChainValues
            .map { JsonUtils.readValue<ProcessChainEntry>(it) }
            .filter { submissionIDs.contains(it.submissionId) }
            .map { it.processChain.id }

        // delete process chains and then submissions
        processChainIDs.forEach { processChainMap.remove(it).coAwait() }
        submissionIDs.forEach { submissionMap.remove(it).coAwait() }

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
    val submissionLock = sharedData.getLock(LOCK_SUBMISSIONS).coAwait()
    try {
      val processChainLock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
      try {
        if (submissions.coAwait().get(submissionId).coAwait() == null) {
          throw NoSuchElementException("There is no submission with ID `$submissionId'")
        }
        val map = this.processChains.coAwait()
        for (processChain in processChains) {
          val e = ProcessChainEntry(processChainEntryID.getAndIncrement(),
              processChain, submissionId, status)
          map.put(processChain.id, JsonUtils.writeValueAsString(e)).coAwait()
        }
      } finally {
        processChainLock.release()
      }
    } finally {
      submissionLock.release()
    }
  }

  private suspend fun findProcessChainEntries(): List<ProcessChainEntry> {
    val map = processChains.coAwait()
    val values = awaitResult<List<String>> { map.values(it) }
    return values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
        .sortedBy { it.serial }
  }

  override suspend fun findProcessChains(submissionId: String?,
      status: ProcessChainStatus?, size: Int, offset: Int, order: Int,
      excludeExecutables: Boolean) = findProcessChainEntries()
          .filter {
            (submissionId == null || it.submissionId == submissionId) &&
                (status == null || it.status == status)
          }
          .let { if (order < 0) it.reversed() else it }
          .drop(offset)
          .let { if (size >= 0) it.take(size) else it }
          .map {
            val pc = if (excludeExecutables) {
              it.processChain.copy(executables = emptyList())
            } else {
              it.processChain
            }
            Pair(pc, it.submissionId)
          }

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

  override suspend fun findProcessChainRequiredCapabilities(status: ProcessChainStatus):
      List<Pair<Collection<String>, IntRange>> {
    return findProcessChainEntries().filter { it.status == status }
        .groupBy { it.processChain.requiredCapabilities }
        .map { (rcs, pcs) ->
          rcs to pcs.minOf { it.processChain.priority }..pcs.maxOf { it.processChain.priority }
        }
  }

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    return processChains.coAwait().get(processChainId).coAwait()?.let {
      JsonUtils.readValue<ProcessChainEntry>(it).processChain
    }
  }

  override suspend fun countProcessChains(submissionId: String?,
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?,
      minPriority: Int?): Long =
      findProcessChainEntries()
          .count {
            (submissionId == null || it.submissionId == submissionId) &&
                (status == null || it.status == status) &&
                (requiredCapabilities == null ||
                    (it.processChain.requiredCapabilities.size == requiredCapabilities.size &&
                        it.processChain.requiredCapabilities.containsAll(requiredCapabilities))) &&
                (minPriority == null || it.processChain.priority >= minPriority)
          }
          .toLong()

  override suspend fun countProcessChainsPerStatus(submissionId: String?):
      Map<ProcessChainStatus, Long> {
    val result = mutableMapOf<ProcessChainStatus, Long>()
    findProcessChainEntries().forEach { pc ->
      if (submissionId == null || pc.submissionId == submissionId) {
        result.merge(pc.status, 1L) { v, _ -> v + 1L }
      }
    }
    return result
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?,
      minPriority: Int?): ProcessChain? {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
    try {
      val map = processChains.coAwait()
      val values = awaitResult<List<String>> { map.values(it) }
      val entry = values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .filter { it.status == currentStatus && (requiredCapabilities == null ||
              (it.processChain.requiredCapabilities.size == requiredCapabilities.size &&
                  it.processChain.requiredCapabilities.containsAll(requiredCapabilities))) &&
              (minPriority == null || it.processChain.priority >= minPriority) }
          .sortedWith(compareByDescending<ProcessChainEntry> { it.processChain.priority }.thenBy { it.serial })
          .firstOrNull()
      return entry?.let {
        val newEntry = it.copy(status = newStatus)
        map.put(it.processChain.id, JsonUtils.writeValueAsString(newEntry)).coAwait()
        it.processChain
      }
    } finally {
      lock.release()
    }
  }

  override suspend fun autoResumeProcessChains(now: Instant) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
    try {
      val map = processChains.coAwait()
      val values = awaitResult<List<String>> { map.values(it) }
      values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .filter { it.status == ProcessChainStatus.PAUSED &&
              it.runs.lastOrNull()?.autoResumeAfter != null &&
              it.runs.lastOrNull()?.autoResumeAfter?.isBefore(now) == true }
          .forEach { e ->
              val newEntry = e.copy(status = ProcessChainStatus.REGISTERED)
              map.put(e.processChain.id, JsonUtils.writeValueAsString(newEntry)).coAwait()
          }
    } finally {
      lock.release()
    }
  }

  override suspend fun existsProcessChain(currentStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>?): Boolean {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
    try {
      val map = processChains.coAwait()
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
    val map = processChains.coAwait()
    val str = map.get(processChainId).coAwait() ?: throw NoSuchElementException(
        "There is no process chain with ID `$processChainId'")
    return JsonUtils.readValue(str)
  }

  private suspend fun updateProcessChain(processChainId: String,
      updater: (ProcessChainEntry) -> ProcessChainEntry) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
    try {
      val map = processChains.coAwait()
      map.get(processChainId).coAwait()?.let {
        val entry = JsonUtils.readValue<ProcessChainEntry>(it)
        val newEntry = updater(entry)
        map.put(processChainId, JsonUtils.writeValueAsString(newEntry)).coAwait()
      }
    } finally {
      lock.release()
    }
  }

  override suspend fun getProcessChainRuns(processChainId: String): List<Run> {
    return getProcessChainEntryById(processChainId).runs
  }

  override suspend fun addProcessChainRun(processChainId: String,
      agentId: String, startTime: Instant): Long {
    val n = AtomicLong()
    updateProcessChain(processChainId) {
      val r = it.copy(runs = it.runs + Run(agentId, startTime))
      n.set(r.runs.size.toLong())
      r
    }
    return n.get()
  }

  override suspend fun deleteLastProcessChainRun(processChainId: String) {
    updateProcessChain(processChainId) { e ->
      val l = e.runs.lastOrNull()
      if (l != null) {
        e.copy(runs = e.runs.dropLast(1))
      } else {
        e
      }
    }
  }

  override suspend fun deleteAllProcessChainRuns(processChainId: String) {
    updateProcessChain(processChainId) { it.copy(runs = emptyList()) }
  }

  override suspend fun getProcessChainRun(processChainId: String, runNumber: Long): Run? {
    return getProcessChainEntryById(processChainId).runs.getOrNull(runNumber.toInt() - 1)
  }

  override suspend fun getLastProcessChainRun(processChainId: String): Run? {
    return getProcessChainEntryById(processChainId).runs.lastOrNull()
  }

  override suspend fun finishProcessChainRun(processChainId: String, runNumber: Long,
      endTime: Instant, status: ProcessChainStatus, errorMessage: String?,
      autoResumeAfter: Instant?) {
    if (runNumber < 1) {
      throw NoSuchElementException("There is no run $runNumber")
    }

    // make sure the process chain exists
    getProcessChainEntryById(processChainId)

    val index = runNumber - 1
    updateProcessChain(processChainId) {
      if (it.runs.isEmpty() || it.runs.size <= index) {
        throw NoSuchElementException("There is no run $runNumber")
      } else {
        it.copy(runs = it.runs.toMutableList().also { runs ->
          runs[index.toInt()] = runs[index.toInt()].copy(
              endTime = endTime,
              status = status,
              errorMessage = errorMessage,
              autoResumeAfter = autoResumeAfter
          )
        })
      }
    }
  }

  override suspend fun countProcessChainRuns(processChainId: String): Long =
      getProcessChainEntryById(processChainId).runs.size.toLong()

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
    val lock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
    try {
      val map = processChains.coAwait()
      val values = awaitResult<List<String>> { map.values(it) }
      values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .filter { it.submissionId == submissionId && it.status == currentStatus }
          .forEach { entry ->
            val newEntry = entry.copy(status = newStatus)
            map.put(entry.processChain.id,
              JsonUtils.writeValueAsString(newEntry)).coAwait()
          }
    } finally {
      lock.release()
    }
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus =
      getProcessChainEntryById(processChainId).status

  override suspend fun setProcessChainPriority(processChainId: String,
      priority: Int): Boolean {
    var updated = false
    updateProcessChain(processChainId) { entry ->
      if (entry.processChain.priority != priority && (
              entry.status == ProcessChainStatus.REGISTERED ||
                  entry.status == ProcessChainStatus.RUNNING ||
                  entry.status == ProcessChainStatus.PAUSED)) {
        updated = true
        entry.copy(
            processChain = entry.processChain.copy(priority = priority)
        )
      } else {
        entry
      }
    }
    return updated
  }

  override suspend fun setAllProcessChainsPriority(submissionId: String,
      priority: Int) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_PROCESS_CHAINS).coAwait()
    try {
      val map = processChains.coAwait()
      val values = awaitResult<List<String>> { map.values(it) }
      values.map { JsonUtils.readValue<ProcessChainEntry>(it) }
          .filter { it.submissionId == submissionId &&
              (it.status == ProcessChainStatus.REGISTERED ||
                  it.status == ProcessChainStatus.RUNNING ||
                  it.status == ProcessChainStatus.PAUSED) }
          .forEach { entry ->
            val newEntry = entry.copy(
                processChain = entry.processChain.copy(priority = priority)
            )
            map.put(entry.processChain.id,
                JsonUtils.writeValueAsString(newEntry)).coAwait()
          }
    } finally {
      lock.release()
    }
  }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    updateProcessChain(processChainId) { it.copy(results = results) }
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? =
      getProcessChainEntryById(processChainId).results

  override suspend fun getProcessChainStatusAndResultsIfFinished(processChainIds: Collection<String>):
      Map<String, Pair<ProcessChainStatus, Map<String, List<Any>>?>> {
    val ids = processChainIds.toSet()
    return findProcessChainEntries()
        .filter {
          ids.contains(it.processChain.id) &&
              it.status != ProcessChainStatus.REGISTERED &&
              it.status !== ProcessChainStatus.RUNNING &&
              it.status !== ProcessChainStatus.PAUSED
        }
        .associateBy({ it.processChain.id }, { it.status to it.results })
  }

  private suspend fun searchSubmissions(query: Query, locators: Set<Locator>):
      List<InternalSearchResult> {
    val map = submissions.coAwait()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.readValue<SubmissionEntry>(it) }
        .map { s -> s.serial to SearchResult(s.submission.id, Type.WORKFLOW,
            s.submission.name, if (locators.contains(Locator.ERROR_MESSAGE))
              s.errorMessage else null, s.submission.requiredCapabilities,
            if (locators.contains(Locator.SOURCE)) s.submission.source else null,
            s.submission.status.name, s.submission.startTime, s.submission.endTime) }
        .map { (serial, r) -> InternalSearchResult(serial, r,
            SearchResultMatcher.toMatch(r, query)) }
        .filter { it.matches.isNotEmpty() }
  }

  private suspend fun searchProcessChains(query: Query, locators: Set<Locator>):
      List<InternalSearchResult> {
    val map = processChains.coAwait()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.readValue<ProcessChainEntry>(it) }
        .map { pc -> pc.serial to SearchResult(
            id = pc.processChain.id,
            type = Type.PROCESS_CHAIN,
            name = null,
            errorMessage = if (locators.contains(Locator.ERROR_MESSAGE))
              pc.runs.lastOrNull()?.errorMessage
            else
              null,
            requiredCapabilities = pc.processChain.requiredCapabilities,
            source = null,
            status = pc.status.name,
            startTime = pc.runs.lastOrNull()?.startTime,
            endTime = pc.runs.lastOrNull()?.endTime
        ) }
        .map { (serial, r) -> InternalSearchResult(serial, r,
            SearchResultMatcher.toMatch(r, query)) }
        .filter { it.matches.isNotEmpty() }
  }

  private suspend fun searchUnsorted(query: Query): List<InternalSearchResult> {
    // search in all places by default
    val types = query.types.ifEmpty { Type.entries.toSet() }
    val locators = if (query.terms.isNotEmpty()) {
      query.locators.ifEmpty { Locator.entries.toSet() }
    } else {
      emptySet()
    } + query.filters.map { it.first }

    // search for submissions
    val matchedSubmissions = if (types.contains(Type.WORKFLOW)) {
      searchSubmissions(query, locators)
    } else {
      emptyList()
    }

    // search for process chains
    val matchedProcessChains = if (types.contains(Type.PROCESS_CHAIN)) {
      searchProcessChains(query, locators)
    } else {
      emptyList()
    }

    val allResults = matchedSubmissions + matchedProcessChains

    // only keep those objects that have matches from terms AND filters
    val queryTermsStr = query.terms.map { SearchResultMatcher.termToString(it) }.toSet()
    val filterTermsStr = query.filters.map { SearchResultMatcher.termToString(it.second) }.toSet()
    return allResults.filter { r ->
      (queryTermsStr.isEmpty() || r.matches.any { m -> m.termMatches.any { tm -> queryTermsStr.contains(tm.term) } }) &&
          (filterTermsStr.isEmpty() || r.matches.any { m -> m.termMatches.any { tm -> filterTermsStr.contains(tm.term) } })
    }
  }

  override suspend fun search(query: Query, size: Int, offset: Int,
      order: Int): Collection<SearchResult> {
    if (query == Query() || size == 0) {
      return emptyList()
    }

    val sortedResults = searchUnsorted(query).sortedWith(compareByDescending<InternalSearchResult> { sr ->
      sr.matches.flatMap { it.termMatches }.map { it.term }.distinct().size * order
    }.thenBy{ it.result.type.priority * order }.thenByDescending { it.serial * order })

    val offsetResults = if (offset > 0) {
      sortedResults.drop(offset)
    } else {
      sortedResults
    }

    val limitedResults = if (size >= 0) {
      offsetResults.take(size)
    } else {
      offsetResults
    }

    return limitedResults.map { it.result }
  }

  override suspend fun searchCount(query: Query, type: Type, estimate: Boolean): Long {
    return searchUnsorted(query.copy(types = setOf(type))).size.toLong()
  }
}
