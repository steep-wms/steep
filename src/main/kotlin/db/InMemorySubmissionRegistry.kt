package db

import db.SubmissionRegistry.ProcessChainStatus
import model.Submission
import model.processchain.ProcessChain

/**
 * A submission registry that keeps objects in memory
 * @author Michel Kraemer
 */
class InMemorySubmissionRegistry : SubmissionRegistry {
  private data class ProcessChainEntry(
      val processChain: ProcessChain,
      val submissionId: String,
      var status: ProcessChainStatus,
      var output: Map<String, List<String>>? = null
  )

  private val submissions = mutableMapOf<String, Submission>()
  private val processChains = mutableMapOf<String, ProcessChainEntry>()

  override suspend fun addSubmission(submission: Submission) {
    submissions[submission.id] = submission
  }

  override suspend fun findSubmissions() = submissions.values.toList()

  override suspend fun findSubmissionById(submissionId: String) =
    submissions[submissionId]

  override suspend fun findSubmissionsByStatus(status: Submission.Status, limit: Int?) =
      submissions.values
          .filter { it.status == status }
          .take(limit ?: Integer.MAX_VALUE)
          .map { it }

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    submissions[submissionId]?.let {
      submissions[submissionId] = it.copy(status = status)
    }
  }

  override suspend fun addProcessChain(processChain: ProcessChain,
      submissionId: String, status: ProcessChainStatus) {
    if (!submissions.containsKey(submissionId)) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }
    val e = ProcessChainEntry(processChain, submissionId, status)
    processChains[processChain.id] = e
  }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String) =
      processChains.values
          .filter { it.submissionId == submissionId }
          .map { it.processChain }

  override suspend fun findProcessChainsByStatus(status: ProcessChainStatus, limit: Int?) =
      processChains.values
          .filter { it.status == status }
          .take(limit ?: Integer.MAX_VALUE)
          .map { it.processChain }

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    processChains[processChainId]?.status = status
  }

  override suspend fun getProcessChainStatus(processChainId: String) =
      (processChains[processChainId] ?: throw NoSuchElementException(
          "There is no process chain with ID `$processChainId'")).status

  override suspend fun setProcessChainOutput(processChainId: String,
      output: Map<String, List<String>>?) {
    processChains[processChainId]?.output = output
  }

  override suspend fun getProcessChainOutput(processChainId: String) =
      (processChains[processChainId] ?: throw NoSuchElementException(
          "There is no process chain with ID `$processChainId'")).output
}
