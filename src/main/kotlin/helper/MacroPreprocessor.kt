package helper

import model.macro.Macro
import model.processchain.Argument
import model.workflow.Action
import model.workflow.AnonymousParameter
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.GenericParameter
import model.workflow.IncludeAction
import model.workflow.IncludeOutputParameter
import model.workflow.InputParameter
import model.workflow.Variable
import model.workflow.Workflow

/**
 * Preprocesses workflows and replaces include actions with macro bodies
 * @author Michel Kraemer
 */
object MacroPreprocessor {
  /**
   * Generates sequence numbers for macro IDs
   */
  private class Sequences {
    private val sequences = mutableMapOf<String, Int>()

    /**
     * Get the next sequence for the given [macroId]
     */
    fun next(macroId: String) =
        sequences.merge(macroId, 0) { i, _ -> i + 1 } ?: 0
  }

  /**
   * Maps macro-internal variables to renamed variables whose names are
   * based on the given [macro]'s ID and the given [sequence].
   */
  private class VariableRenames(private val macro: Macro, private val sequence: Int) {
    private val renames = mutableMapOf<String, Variable>()

    /**
     * Rename a variable [v]. Create a new name from the given [macro] and
     * [sequence]. Return the renamed variable and memorize the result.
     *
     * If a variable has already been renamed before, return the memorized
     * renamed variable.
     */
    fun rename(v: Variable): Variable {
      return renames.computeIfAbsent(v.id) {
        val newId = "$${macro.id}$${sequence}$${v.id}"
        v.copy(id = newId)
      }
    }
  }

  /**
   * Preprocess a workflow using the given list of [macros]. Process all
   * include actions and replace them with the macro bodies. Map variables
   * inside included macros to workflow variables, rename internal variables
   * and action IDs.
   */
  fun preprocess(workflow: Workflow, macros: Map<String, Macro>): Workflow {
    val (newActions, newVariables) = preprocess(workflow.actions, macros, Sequences())
    if (newActions === workflow.actions) {
      return workflow
    }

    return workflow.copy(
        vars = workflow.vars + newVariables,
        actions = newActions
    )
  }

  /**
   * Preprocess a list of [actions] using the given list of [macros] and
   * [sequences]
   */
  private fun preprocess(actions: List<Action>, macros: Map<String, Macro>,
      sequences: Sequences): Pair<List<Action>, List<Variable>> {
    val queue = ArrayDeque(actions)
    val newActions = mutableListOf<Action>()
    val newVars = mutableListOf<Variable>()
    var changed = false

    while (queue.isNotEmpty()) {
      when (val a = queue.removeFirst()) {
        is ExecuteAction -> newActions.add(a)

        is ForEachAction -> {
          val (newSubActions, newSubVars) = preprocess(a.actions, macros, sequences)
          if (newSubActions === a.actions && newSubVars.isEmpty()) {
            newActions.add(a)
          } else {
            newActions.add(a.copy(actions = newSubActions))
            newVars.addAll(newSubVars)
            changed = true
          }
        }

        is IncludeAction -> {
          val m = macros[a.macro] ?: throw IllegalArgumentException(
              "Unable to find macro `${a.macro}'")
          val (newSubActions, newSubVars) = expandMacro(a, m, sequences)
          newSubActions.asReversed().forEach { queue.addFirst(it) }
          newVars.addAll(newSubVars)
          changed = true
        }
      }
    }

    return if (changed) {
      newActions to newVars
    } else {
      actions to emptyList()
    }
  }

  /**
   * Expand the given [includeAction] within the context of the given [macro]
   * and [sequences].
   */
  private fun expandMacro(includeAction: IncludeAction, macro: Macro,
      sequences: Sequences): Pair<List<Action>, List<Variable>> {
    val s = sequences.next(macro.id)

    // collect inputs and outputs
    val (inputs, outputs) = collectIncludeParameters(includeAction, macro, s)

    val renames = VariableRenames(macro, s)

    // rename variables
    val renamedVars = macro.vars.map { renames.rename(it) }

    // rename IDs and variables in actions
    val actions = expandActions(macro.actions, macro, inputs, outputs, renames, s)

    return actions to renamedVars
  }

  /**
   * Collect all inputs and outputs of an [includeAction] for a given [macro]
   * and generate default inputs if necessary. Use the given [sequence] to
   * generate IDs for these inputs.
   */
  private fun collectIncludeParameters(includeAction: IncludeAction, macro: Macro,
      sequence: Int): Pair<Map<String, InputParameter>, Map<String, IncludeOutputParameter>> {
    val inputs = mutableMapOf<String, InputParameter>()
    val outputs = mutableMapOf<String, IncludeOutputParameter>()
    for (macroParam in macro.parameters) {
      when (macroParam.type) {
        Argument.Type.INPUT -> {
          var inputParam = includeAction.inputs.find { it.id == macroParam.id }

          // if there are no inputs but macroParam has a default value, add a
          // new generic input parameter
          if (inputParam == null && macroParam.default != null) {
            val newId = "$${macro.id}$${sequence}$${macroParam.id}"
            inputParam = GenericParameter(
                id = newId,
                variable = Variable(
                    id = "$newId\$default",
                    value = macroParam.default
                )
            )
          }

          if (inputParam == null) {
            throw IllegalArgumentException(
                "Missing macro input parameter `${macroParam.id}'. The " +
                    "parameter was not given in the include action and " +
                    "does not have a default value."
            )
          }

          inputs[macroParam.id] = inputParam
        }

        Argument.Type.OUTPUT -> {
          val outputParam =
              includeAction.outputs.find { it.id == macroParam.id }
                  ?: throw IllegalArgumentException(
                      "Missing macro output parameter `${macroParam.id}'. The " +
                          "parameter was not given in the include action and " +
                          "does not have a default value."
                  )
          outputs[macroParam.id] = outputParam
        }
      }
    }
    return Pair(inputs, outputs)
  }

  /**
   * Expand the given list of [actions] within the context of the given [macro],
   * variable [renames], and [sequence]. Recursively map variables to the given
   * [inputs] and [outputs].
   */
  private fun expandActions(actions: List<Action>, macro: Macro,
      inputs: Map<String, InputParameter>, outputs: Map<String, IncludeOutputParameter>,
      renames: VariableRenames, sequence: Int): MutableList<Action> {
    val result = mutableListOf<Action>()
    for (a in actions) {
      val newId = "$${macro.id}$${sequence}$${a.id}"
      when (a) {
        is ExecuteAction -> {
          val actionInputs =
              a.inputs.map { mapInputParameter(it, inputs, renames) }
          val actionOutputs = a.outputs.map { o ->
            outputs[o.variable.id]?.let { includeOutput ->
              o.copy(variable = includeOutput.variable)
            } ?: o.copy(
                variable = renames.rename(o.variable)
            )
          }
          result.add(
              a.copy(
                  id = newId,
                  inputs = actionInputs,
                  outputs = actionOutputs
              )
          )
        }

        is ForEachAction -> {
          result.add(
              a.copy(
                  id = newId,
                  input = inputs[a.input.id]?.variable
                      ?: renames.rename(a.input),
                  enumerator = renames.rename(a.enumerator),
                  output = a.output?.let { o ->
                    outputs[o.id]?.variable ?: renames.rename(o)
                  },
                  yieldToInput = a.yieldToInput?.let { renames.rename(it) },
                  yieldToOutput = a.yieldToOutput?.let { renames.rename(it) },
                  actions = expandActions(a.actions, macro, inputs, outputs, renames, sequence)
              )
          )
        }

        is IncludeAction -> {
          val actionInputs =
              a.inputs.map { mapInputParameter(it, inputs, renames) }
          val actionOutputs = a.outputs.map { o ->
            outputs[o.variable.id]?.let { includeOutput ->
              o.copy(variable = includeOutput.variable)
            } ?: o.copy(
                variable = renames.rename(o.variable)
            )
          }
          result.add(
              a.copy(
                  id = newId,
                  inputs = actionInputs,
                  outputs = actionOutputs
              )
          )
        }
      }
    }
    return result
  }

  /**
   * Map an [actionInputParameter] of an include action to an input from
   * the given map of [inputs] and rename variables accordingly.
   */
  private fun mapInputParameter(actionInputParameter: InputParameter,
      inputs: Map<String, InputParameter>,
      renames: VariableRenames): InputParameter {
    return when (actionInputParameter) {
      is AnonymousParameter -> actionInputParameter
      is GenericParameter -> {
        inputs[actionInputParameter.variable.id]?.let { input ->
          when (input) {
            is AnonymousParameter -> input.copy(id = actionInputParameter.id)
            is GenericParameter -> input.copy(id = actionInputParameter.id)
          }
        } ?: actionInputParameter.copy(
            variable = renames.rename(actionInputParameter.variable)
        )
      }
    }
  }
}
