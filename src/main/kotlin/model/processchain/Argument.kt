package model.processchain

import com.fasterxml.jackson.annotation.JsonValue
import helper.UniqueID

/**
 * A program argument
 * @param id a unique identifier for this argument
 * @param label an optional label (e.g. `--input` or `-i`)
 * @param variable a variable holding the argument's value
 * @param type the argument's type (describes if this is an input, an output,
 * or a normal argument)
 * @param dataType the argument's data type. Describes the type of the argument
 * value and, in particular (given that the argument is an input or output
 * argument) whether the value points to a file or a directory.
 * @author Michel Kraemer
 */
data class Argument(
    val id: String = UniqueID.next(),
    val label: String? = null,
    val variable: ArgumentVariable,
    val type: Type,
    val dataType: String = DATA_TYPE_STRING
) {
  enum class Type(@JsonValue val type: String) {
    INPUT("input"),
    OUTPUT("output"),
    ARGUMENT("argument")
  }

  companion object {
    const val DATA_TYPE_INTEGER = "integer"
    const val DATA_TYPE_FLOAT = "float"
    const val DATA_TYPE_STRING = "string"
    const val DATA_TYPE_BOOLEAN = "boolean"
    const val DATA_TYPE_DIRECTORY = "directory"
  }
}
