package model.processchain

import helper.UniqueID
import model.metadata.Service

/**
 * An executable in a process chain
 * @param id the executable's identifier
 * @param path the path to the program to execute
 * @param arguments the program arguments
 * @param runtime the runtime environment for this executable
 * @author Michel Kraemer
 */
data class Executable(
    val id: String = UniqueID.next(),
    val path: String,
    val arguments: List<Argument>,
    val runtime: String = Service.RUNTIME_OTHER,
    val runtimeArgs: List<Argument> = emptyList()
)
