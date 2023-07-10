package model.macro

import model.workflow.Action
import model.workflow.Variable

/**
 * A macro represents a reusable list of actions that can be included into a
 * [model.workflow.Workflow] using a [model.workflow.IncludeAction].
 * @param id a unique macro identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param parameters an optional list of parameters that can be accessed from
 * outside the macro, for example, to pass arguments (i.e. inputs) to the macro
 * or to access return values (i.e. outputs).
 * @param vars an optional list of private variables that can be used inside
 * the macro. These variables are not accessible from the outside.
 * @param actions the actions to execute
 * @author Michel Kraemer
 */
data class Macro(
    val id: String,
    val name: String,
    val description: String,
    val parameters: List<MacroParameter> = emptyList(),
    val vars: List<Variable> = emptyList(),
    val actions: List<Action>
)
