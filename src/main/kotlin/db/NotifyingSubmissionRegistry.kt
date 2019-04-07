package db

import AddressConstants
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.Submission
import model.processchain.ProcessChain
import java.time.Instant

/**
 * Wraps around a submission registry and published events whenever the
 * registry's contents have changed.
 * @author Michel Kraemer
 */
class NotifyingSubmissionRegistry(private val delegate: SubmissionRegistry, private val vertx: Vertx) :
    SubmissionRegistry by delegate {
  override suspend fun addSubmission(submission: Submission) {
    delegate.addSubmission(submission)
    vertx.eventBus().publish(AddressConstants.SUBMISSION_ADDED, JsonUtils.toJson(submission))
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val s = delegate.fetchNextSubmission(currentStatus, newStatus)
    if (s != null) {
      vertx.eventBus().publish(AddressConstants.SUBMISSION_STATUS_CHANGED, json {
        obj(
            "submissionId" to s.id,
            "status" to newStatus.name
        )
      })
    }
    return s
  }

  override suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant) {
    delegate.setSubmissionStartTime(submissionId, startTime)
    vertx.eventBus().publish(AddressConstants.SUBMISSION_STARTTIME_CHANGED, json {
      obj(
          "submissionId" to submissionId,
          "startTime" to startTime
      )
    })
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    delegate.setSubmissionEndTime(submissionId, endTime)
    vertx.eventBus().publish(AddressConstants.SUBMISSION_ENDTIME_CHANGED, json {
      obj(
          "submissionId" to submissionId,
          "endTime" to endTime
      )
    })
  }

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    delegate.setSubmissionStatus(submissionId, status)
    vertx.eventBus().publish(AddressConstants.SUBMISSION_STATUS_CHANGED, json {
      obj(
          "submissionId" to submissionId,
          "status" to status.name
      )
    })
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>, submissionId: String, status: SubmissionRegistry.ProcessChainStatus) {
    delegate.addProcessChains(processChains, submissionId, status)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAINS_ADDED, json {
      obj(
          "processChains" to processChains.map { JsonUtils.toJson(it) },
          "submissionId" to submissionId,
          "status" to status.name
      )
    })
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain? {
    val pc = delegate.fetchNextProcessChain(currentStatus, newStatus)
    if (pc != null) {
      vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_STATUS_CHANGED, json {
        obj(
            "processChainId" to pc.id,
            "status" to newStatus.name
        )
      })
    }
    return pc
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    delegate.setProcessChainStartTime(processChainId, startTime)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_STARTTIME_CHANGED, json {
      obj(
          "processChainId" to processChainId,
          "startTime" to startTime
      )
    })
  }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    delegate.setProcessChainEndTime(processChainId, endTime)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_ENDTIME_CHANGED, json {
      obj(
          "processChainId" to processChainId,
          "endTime" to endTime
      )
    })
  }

  override suspend fun setProcessChainStatus(processChainId: String, status: ProcessChainStatus) {
    delegate.setProcessChainStatus(processChainId, status)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_STATUS_CHANGED, json {
      obj(
          "processChainId" to processChainId,
          "status" to status.name
      )
    })
  }

  override suspend fun setProcessChainResults(processChainId: String, results: Map<String, List<String>>?) {
    delegate.setProcessChainResults(processChainId, results)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_RESULTS_CHANGED, json {
      obj(
          "processChainId" to processChainId,
          "results" to results
      )
    })
  }

  override suspend fun setProcessChainErrorMessage(processChainId: String, errorMessage: String?) {
    delegate.setProcessChainErrorMessage(processChainId, errorMessage)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_ERRORMESSAGE_CHANGED, json {
      obj(
          "processChainId" to processChainId,
          "errorMessage" to errorMessage
      )
    })
  }
}
