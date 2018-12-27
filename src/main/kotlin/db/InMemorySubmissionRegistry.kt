package db

import db.SubmissionRegistry.ProcessChainStatus
import model.processchain.ProcessChain

/**
 * A submission registry that keeps objects in memory
 * @author Michel Kraemer
 */
class InMemorySubmissionRegistry : SubmissionRegistry {
  private data class ProcessChainEntry(
      val processChain: ProcessChain,
      val status: ProcessChainStatus
  )

  private val processChains = emptyMap<String, ProcessChainEntry>()

  override suspend fun findProcessChainsByStatus(status: ProcessChainStatus,
      limit: Int?) = processChains.entries
      .map { it.value }
      .filter { it.status == status }
      .take(limit ?: Integer.MAX_VALUE)
      .map { it.processChain }

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    TODO("not implemented")
  }

  override suspend fun setProcessChainOutput(processChainId: String,
      output: List<String>) {
    TODO("not implemented")
  }
}
