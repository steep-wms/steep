package model

import helper.UniqueID
import model.workflow.Workflow
import java.time.Instant

/**
 * A submission
 * @param id the submission's unique identifier
 * @param workflow the workflow to execute
 * @param startTime the time when the workflow was started
 * @param endTime the time when the workflow has finished
 * @param status the current execution status
 * @author Michel Kraemer
 */
data class Submission(
    val id: String = UniqueID.next(),
    val workflow: Workflow,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val status: Status = Status.ACCEPTED
) {
  enum class Status {
    ACCEPTED,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    ERROR
  }
}
