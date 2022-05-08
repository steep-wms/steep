package model

import com.fasterxml.jackson.annotation.JsonInclude
import helper.UniqueID
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import model.metadata.Service
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
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val priority: Int = workflow.priority,
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
    private fun collectRequiredCapabilities(actions: JsonArray,
        serviceMetadata: Map<String, Service>): Set<String> {
      val result = mutableSetOf<String>()
      for (i in 0 until actions.size()) {
        val a = actions.getJsonObject(i)
        when (a.getString("type")) {
          "execute" -> {
            val service = serviceMetadata[a.getString("service")]
            if (service != null) {
              result.addAll(service.requiredCapabilities)
            }
          }

          "for" -> result.addAll(collectRequiredCapabilities(
              a.getJsonArray("actions"), serviceMetadata))
        }
      }
      return result
    }

    /**
     * Use the metadata of the given [services] to calculate a set of
     * capabilities required to execute the given [submission]
     */
    fun collectRequiredCapabilities(submission: JsonObject, services: List<Service>): Set<String> {
      val serviceMetadata = services.associateBy { it.id }
      val workflow = submission.getJsonObject("workflow")
      val actions = workflow.getJsonArray("actions")
      return collectRequiredCapabilities(actions, serviceMetadata)
    }
  }
}
