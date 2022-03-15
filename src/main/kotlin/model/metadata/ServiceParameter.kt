package model.metadata

import model.processchain.Argument

/**
 * Service metadata parameters
 * @param id a unique parameter identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param type parameter type
 * @param cardinality parameter cardinality (i.e. how many times the parameter
 * may appear)
 * @param dataType parameter data type
 * @param default the default value for this parameter
 * @param fileSuffix output file extension or suffix
 * @param label parameter name on the command line
 * @author Michel Kraemer
 */
data class ServiceParameter(
    val id: String,
    val name: String,
    val description: String,
    val type: Argument.Type,
    val cardinality: Cardinality,
    val dataType: String = Argument.DATA_TYPE_STRING,
    val default: Any? = null,
    val fileSuffix: String? = null,
    val label: String? = null
)
