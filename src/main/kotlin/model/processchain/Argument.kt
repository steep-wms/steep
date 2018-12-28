package model.processchain

import helper.UniqueID

data class Argument(
    val id: String = UniqueID.next(),
    val label: String? = null,
    val value: String,
    val type: Type,
    val dataType: String = DATA_TYPE_STRING
) {
  enum class Type {
    INPUT,
    OUTPUT,
    ARGUMENT
  }

  companion object {
    const val DATA_TYPE_INTEGER = "integer"
    const val DATA_TYPE_FLOAT = "float"
    const val DATA_TYPE_STRING = "string"
    const val DATA_TYPE_BOOLEAN = "boolean"
    const val DATA_TYPE_DIRECTORY = "directory"
  }
}
