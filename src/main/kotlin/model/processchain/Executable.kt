package model.processchain

import helper.UniqueID

data class Executable(
    val id: String = UniqueID.next(),
    val predecessors: Set<ProcessChain>,
    val path: String,
    val arguments: List<Argument>,
    val workingDir: String
)
