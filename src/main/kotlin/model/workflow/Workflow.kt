package model.workflow

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * A workflow
 * @param name a human-readable name
 * @param priority a priority used during scheduling. Process chains generated
 * from workflows with higher priorities will be scheduled before those with
 * lower priorities.
 * @param retries default retry policies that should be used within the
 * workflow unless more specific retry policies are defined elsewhere
 * @param vars the variables used within the workflow
 * @param actions the actions to execute
 * @author Michel Kraemer
 */
data class Workflow(
    val api: String = "4.5.0",
    val name: String? = null,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val priority: Int = 0,
    val retries: RetryPolicyDefaults? = null,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val vars: List<Variable> = emptyList(),
    val actions: List<Action> = emptyList()
)
