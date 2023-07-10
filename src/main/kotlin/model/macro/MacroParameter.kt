package model.macro

import model.processchain.Argument

/**
 * Macro parameters
 * @param id a unique parameter identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param type parameter type
 * @param default an optional default value for this parameter
 * @author Michel Kraemer
 */
data class MacroParameter(
    val id: String,
    val name: String,
    val description: String,
    val type: Argument.Type,
    val default: Any? = null,
)
