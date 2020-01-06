package db

import AddressConstants
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.kotlin.core.eventbus.DeliveryOptions
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.Submission
import model.processchain.ProcessChain
import model.workflow.Workflow
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
    vertx.eventBus().publish(AddressConstants.SUBMISSION_ADDED, {
      // do not serialize workflow
      JsonUtils.toJson(submission.copy(workflow = Workflow())).also { it.remove("workflow") }
    }, DeliveryOptions(codecName = "lazyjsonobject"))
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

  override suspend fun setSubmissionErrorMessage(submissionId: String, errorMessage: String?) {
    delegate.setSubmissionErrorMessage(submissionId, errorMessage)
    vertx.eventBus().publish(AddressConstants.SUBMISSION_ERRORMESSAGE_CHANGED, json {
      obj(
          "submissionId" to submissionId,
          "errorMessage" to errorMessage
      )
    })
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    delegate.addProcessChains(processChains, submissionId, status)

    val options = DeliveryOptions(codecName = "lazyjsonobject")
    vertx.eventBus().publish(AddressConstants.PROCESSCHAINS_ADDED, {
      json {
        obj(
            "processChains" to processChains.map { pc ->
              // do not serialize executables
              JsonUtils.toJson(pc.copy(executables = emptyList()))
                  .also { it.remove("executables") }
            },
            "submissionId" to submissionId,
            "status" to status.name
        )
      }
    }, options)

    vertx.eventBus().publish(AddressConstants.PROCESSCHAINS_ADDED_SIZE, {
      json {
        obj(
            "processChainsSize" to processChains.size,
            "submissionId" to submissionId,
            "status" to status.name
        )
      }
    }, options)
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain? {
    val pc = delegate.fetchNextProcessChain(currentStatus, newStatus)
    if (pc != null) {
      vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_STATUS_CHANGED, json {
        obj(
            "processChainId" to pc.id,
            "submissionId" to delegate.getProcessChainSubmissionId(pc.id),
            "status" to newStatus.name,
            "previousStatus" to currentStatus.name
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
    val previous = delegate.getProcessChainStatus(processChainId)
    delegate.setProcessChainStatus(processChainId, status)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_STATUS_CHANGED, json {
      obj(
          "processChainId" to processChainId,
          "submissionId" to delegate.getProcessChainSubmissionId(processChainId),
          "status" to status.name,
          "previousStatus" to previous.name
      )
    })
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    delegate.setProcessChainStatus(processChainId, currentStatus, newStatus)
    val actualStatus = delegate.getProcessChainStatus(processChainId)
    if (actualStatus == newStatus) {
      vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_STATUS_CHANGED, json {
        obj(
            "processChainId" to processChainId,
            "submissionId" to delegate.getProcessChainSubmissionId(processChainId),
            "status" to newStatus.name,
            "previousStatus" to currentStatus.name
        )
      })
    }
  }

  override suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    delegate.setAllProcessChainsStatus(submissionId, currentStatus, newStatus)
    vertx.eventBus().publish(AddressConstants.PROCESSCHAIN_ALL_STATUS_CHANGED, json {
      obj(
          "submissionId" to submissionId,
          "currentStatus" to currentStatus.name,
          "newStatus" to newStatus.name
      )
    })
  }

  override suspend fun setProcessChainResults(processChainId: String, results: Map<String, List<Any>>?) {
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
