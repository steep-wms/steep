package model

import helper.UniqueID
import model.metadata.Service
import model.workflow.Action
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
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
    CANCELLED,
    SUCCESS,
    PARTIAL_SUCCESS,
    ERROR
  }

  companion object {
    private fun collectRequiredCapabilities(actions: List<Action>,
        serviceMetadata: Map<String, Service>): Set<String> {
      val result = mutableSetOf<String>()
      for (a in actions) {
        when (a) {
          is ExecuteAction -> {
            val service = serviceMetadata[a.service]
            if (service != null) {
              result.addAll(service.requiredCapabilities)
            }
          }

          is ForEachAction ->
            result.addAll(collectRequiredCapabilities(a.actions, serviceMetadata))
        }
      }
      return result
    }
  }

  /**
   * Use the metadata of the given [services] to calculate a set of
   * capabilities required to execute this submission
   */
  fun collectRequiredCapabilities(services: List<Service>): Set<String> {
    val serviceMetadata = services.associateBy { it.id }
    return collectRequiredCapabilities(workflow.actions, serviceMetadata)
  }
}
