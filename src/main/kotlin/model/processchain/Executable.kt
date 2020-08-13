package model.processchain

import helper.UniqueID
import model.metadata.Service

/**
 * An executable in a process chain
 * @param id the executable's identifier (typically the name of the
 * processing service that should be called)
 * @param path the path to the program to execute
 * @param arguments the program arguments
 * @param runtime the runtime environment for this executable
 * @param serviceId the ID of the processing service to be called (may be
 * `null` if an executable should be called that does not refer to a service
 * or if the object has been created prior to Steep v5.4.0)
 * @author Michel Kraemer
 */
data class Executable(
    val id: String = UniqueID.next(),
    val path: String,
    val arguments: List<Argument>,
    val runtime: String = Service.RUNTIME_OTHER,
    val runtimeArgs: List<Argument> = emptyList(),
    val serviceId: String? = null
)
