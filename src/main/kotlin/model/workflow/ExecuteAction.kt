package model.workflow

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import helper.UniqueID
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
 * @param maxInactivity an optional timeout policy that defines how long the
 * execution of the service can take without producing any output (i.e.
 * without writing anything to the standard output and error streams) before it
 * is automatically aborted. This value overrides any maximum inactivity rule
 * defined in the service metadata.
 * @param maxRuntime an optional timeout policy that defines how long the
 * execution of the service can take before it is automatically aborted, even
 * if the service regularly writes to the standard output and error streams. Can
 * be overridden in the workflow (see [maxRuntime]). This value overrides any
 * maximum runtime rule defined in the service metadata.
 * @param deadline an optional timeout policy that defines how long the
 * execution of the service can take at all (including all retries and their
 * associated delays) until it is aborted. Can be overridden in the workflow
 * (see [deadline]). This value overrides any deadline defined in the service
 * metadata.
 * @author Michel Kraemer
 */
data class ExecuteAction(
    override val id: String = UniqueID.next(),
    val service: String,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val inputs: List<InputParameter> = emptyList(),
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val outputs: List<OutputParameter> = emptyList(),
    val retries: RetryPolicy? = null,
    val maxInactivity: TimeoutPolicy? = null,
    val maxRuntime: TimeoutPolicy? = null,
    val deadline: TimeoutPolicy? = null,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    override val dependsOn: List<String> = emptyList()
) : Action
