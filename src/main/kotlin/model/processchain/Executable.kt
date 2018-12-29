package model.processchain

import helper.UniqueID

/**
 * An executable in a process chain
 * @param id the executable's unique identifier
 * @param path the path to the program to execute
 * @param arguments the program arguments
 * @author Michel Kraemer
 */
data class Executable(
    val id: String = UniqueID.next(),
    val path: String,
    val arguments: List<Argument>
)
