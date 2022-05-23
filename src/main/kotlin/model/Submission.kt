package model

import com.fasterxml.jackson.annotation.JsonInclude
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
 * @param name the submission's name. Derived from the [workflow]'s name
 * @param priority the submission's priority. Derived from the [workflow]'s
 * priority [Workflow.priority] but can be overridden. Process chains generated
 * from submissions with higher priorities will be scheduled before those with
 * lower priorities.
 * @param startTime the time when the workflow was started
 * @param endTime the time when the workflow has finished
 * @param status the current execution status
 * @param requiredCapabilities a set of strings specifying capabilities a host
 * system must provide to be able to execute the workflow
 * @param source the original, unaltered workflow source as it was submitted
 * through Steep's HTTP endpoint (may be `null` if the workflow was not
 * submitted through the HTTP endpoint or if the source is unavailable)
 * @author Michel Kraemer
 */
data class Submission(
    val id: String = UniqueID.next(),
    val workflow: Workflow,
    val name: String? = workflow.name,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val priority: Int = workflow.priority,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val status: Status = Status.ACCEPTED,
    val requiredCapabilities: Set<String> = emptySet(),
    val source: String? = null
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

          is ForEachAction -> result.addAll(collectRequiredCapabilities(
              a.actions, serviceMetadata))
        }
      }
      return result
    }

    /**
     * Use the metadata of the given [services] to calculate a set of
     * capabilities required to execute the given [workflow]
     */
    fun collectRequiredCapabilities(workflow: Workflow, services: List<Service>): Set<String> {
      val serviceMetadata = services.associateBy { it.id }
      return collectRequiredCapabilities(workflow.actions, serviceMetadata)
    }
  }
}
