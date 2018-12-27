package model.processchain

import helper.UniqueID

data class Argument(
    val id: String = UniqueID.next(),
    val label: String?,
    val value: Any,
    val type: Type
) {
  enum class Type {
    INPUT,
    OUTPUT,
    ARGUMENT
  }
}
