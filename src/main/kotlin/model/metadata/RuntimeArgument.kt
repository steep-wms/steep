package model.metadata

import model.processchain.Argument

/**
 * Service runtime arguments
 * @param id a unique argument identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param dataType argument data type
 * @param label argument's name on the command line
 * @param value the argument's value
 * @author Michel Kraemer
 */
data class RuntimeArgument(
    val id: String,
    val name: String,
    val description: String,
    val dataType: String = Argument.DATA_TYPE_STRING,
    val label: String? = null,
    val value: String? = null
)
