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
      var output: List<String>? = null
  )

  private val submissions = mutableMapOf<String, Submission>()
  private val processChains = mutableMapOf<String, ProcessChainEntry>()

  override suspend fun addSubmission(submission: Submission) {
    submissions[submission.id] = submission
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? =
    submissions[submissionId]

  override suspend fun addProcessChain(processChain: ProcessChain,
      submissionId: String, status: ProcessChainStatus) {
    if (!submissions.containsKey(submissionId)) {
      throw NoSuchElementException("There is no submission with ID `$submissionId'")
    }
    val e = ProcessChainEntry(processChain, submissionId, status)
    processChains[processChain.id] = e
  }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String): List<ProcessChain> =
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

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus =
      (processChains[processChainId] ?: throw NoSuchElementException(
          "There is no process chain with ID `$processChainId'")).status

  override suspend fun setProcessChainOutput(processChainId: String,
      output: List<String>?) {
    processChains[processChainId]?.output = output
  }

  override suspend fun getProcessChainOutput(processChainId: String): List<String>? =
      (processChains[processChainId] ?: throw NoSuchElementException(
          "There is no process chain with ID `$processChainId'")).output
}
