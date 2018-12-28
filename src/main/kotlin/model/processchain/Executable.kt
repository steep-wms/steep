package model.processchain

import helper.UniqueID

data class Executable(
    val id: String = UniqueID.next(),
    val path: String,
    val arguments: List<Argument>
)
