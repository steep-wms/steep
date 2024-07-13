package model.processchain

import helper.UniqueID
import model.metadata.Service
import model.retry.RetryPolicy
import model.timeout.TimeoutPolicy

/**
 * An executable in a process chain
 * @param id an identifier (does not have to be unique). Typically refers to
 * the `id` of the [model.workflow.ExecuteAction] from which the executable was
 * derived. Possibly suffixed with a dollar sign `$` and a number denoting the
 * iteration of an enclosing [model.workflow.ForEachAction] (e.g. `myaction$1`)
 * or nested [model.workflow.ForEachAction]s (e.g. `myaction$2$1`).
 * @param path the path to the program to execute
 * @param arguments the program arguments
 * @param runtime the name of the runtime for this executable
 * @param serviceId the ID of the processing service to be called (may be
 * `null` if an executable should be called that does not refer to a service
 * or if the object has been created prior to Steep v5.4.0)
 * @param retries optional rules that define when and how often this
 * executable should be restarted in case an error has occurred
 * @param maxInactivity an optional timeout policy that defines how long the
 * executable can run without producing any output (i.e. without writing
 * anything to the standard output and error streams) before it is
 * automatically aborted
 * @param maxRuntime an optional timeout policy that defines how long the
 * executable can run before it is automatically aborted, even if it regularly
 * writes to the standard output and error streams
 * @param deadline an optional timeout policy that defines how long the
 * execution can take at all (including all retries and their associated
 * delays) until it is aborted
 * @author Michel Kraemer
 */
data class Executable(
    val id: String = UniqueID.next(),
    val path: String,
    val serviceId: String,
    val arguments: List<Argument>,
    val runtime: String = Service.RUNTIME_OTHER,
    val runtimeArgs: List<Argument> = emptyList(),
    val retries: RetryPolicy? = null,
    val maxInactivity: TimeoutPolicy? = null,
    val maxRuntime: TimeoutPolicy? = null,
    val deadline: TimeoutPolicy? = null
)
