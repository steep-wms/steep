package model.processchain

import helper.UniqueID

/**
 * A process chain describes a set of actions (i.e. [Executable]s) that should
 * be executed by [agent.Agent]s
 * @param id the process chain's unique identifier
 * @param predecessors the process chain's predecessors (i.e. the process chains
 * that should be executed before this one). This set may be empty if there are
 * no predecessors.
 * @param executables the list of [Executable]s to actually be executed
 */
data class ProcessChain(
    val id: String = UniqueID.next(),
    val predecessors: Set<ProcessChain> = emptySet(),
    val executables: List<Executable> = emptyList()
)
