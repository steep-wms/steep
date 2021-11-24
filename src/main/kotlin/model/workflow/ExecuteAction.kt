package model.workflow

import model.retry.RetryPolicy
import model.timeout.TimeoutPolicy

/**
 * A workflow action that executes a service
 * @param service the name of the service to execute
 * @param inputs the service inputs
 * @param outputs the service outputs
 * @param retries optional rules that define when and how often the execution
 * of the service should be retried in case an error has occurred. This value
 * overrides any retry policy defined in the service metadata.
 * @param maxRuntime an optional timeout policy that defines how long the
 * execution of the service can take before it is automatically aborted, even
 * if the service regularly writes to the standard output and error streams. Can
 * be overridden in the workflow (see [model.workflow.ExecuteAction.maxRuntime]).
 * This value overrides any maximum runtime rule defined in the service metadata.
 * @author Michel Kraemer
 */
data class ExecuteAction(
    val service: String,
    val inputs: List<GenericParameter> = emptyList(),
    val outputs: List<OutputParameter> = emptyList(),
    val retries: RetryPolicy? = null,
    val maxRuntime: TimeoutPolicy? = null
) : Action
