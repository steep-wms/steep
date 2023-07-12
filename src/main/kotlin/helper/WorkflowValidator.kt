package helper

import model.macro.Macro
import model.macro.MacroParameter
import model.processchain.Argument
import model.workflow.Action
import model.workflow.AnonymousParameter
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.GenericParameter
import model.workflow.IncludeAction
import model.workflow.InputParameter
import model.workflow.Parameter
import model.workflow.Variable
import model.workflow.Workflow

/**
 * Validates workflows and macros. Checks for common mistakes and
 * inconsistencies.
 * @author Michel Kraemer
 */
class WorkflowValidator private constructor(private val type: Type) {
  /**
   * The result of a failed validation. It has a human-readable [message] and
   * optional a string with [details] about the error and how it can be fixed.
   */
  data class ValidationError(val message: String, val details: String? = null,
      val path: List<String>)

  private enum class Type(val rootPath: String) {
    WORKFLOW("workflow"),
    MACRO("macro")
  }

  companion object {
    /**
     * Validate a [workflow]. Return a list of errors. This list will be
     * empty if the workflow is OK and no errors were found.
     */
    fun validate(workflow: Workflow): List<ValidationError> {
      return WorkflowValidator(Type.WORKFLOW)
          .validate(workflow.vars, workflow.actions)
    }

    /**
     * Validate a [macro]. Return a list of errors. This list will be
     * empty if the macro is OK and no errors were found.
     */
    fun validate(macro: Macro): List<ValidationError> {
      val v = WorkflowValidator(Type.MACRO)

      val results = mutableListOf<ValidationError>()
      v.duplicateParameterIds(macro.parameters, results)
      v.redeclaredParameter(macro.vars, macro.parameters, results)

      val inputs = macro.parameters
          .filter { it.type == Argument.Type.INPUT }
          .associateBy { it.id }

      v.inputParameterWithValue(macro.actions, inputs, results)
      v.inputAsOutput(macro.actions, inputs, results)

      val outputs = macro.parameters
          .filter { it.type == Argument.Type.OUTPUT }
          .associateBy { it.id }

      v.outputAsInput(macro.actions, outputs, results)
      v.parameterAsEnumerator(macro.actions, macro.parameters, results)

      return results + v.validate(macro.vars, macro.actions, inputs)
    }
  }

  /**
   * Validate variables and actions. Return a list of errors. This list will be
   * empty if the variables and actions are OK and no errors were found.
   */
  private fun validate(vars: List<Variable>, actions: List<Action>,
      macroInputs: Map<String, MacroParameter> = emptyMap()): List<ValidationError> {
    val results = mutableListOf<ValidationError>()
    outputsWithValues(actions, results)
    duplicateIds(vars, actions, results)
    missingDependsOnTargets(actions, results)
    missingInputValues(actions, macroInputs, results)
    reuseOutput(actions, results)
    reuseEnumerator(actions, results)
    enumeratorAsInput(actions, results)
    scoping(vars, actions, results)
    return results
  }

  /**
   * Recursively visit a list of [actions]. Call the given [executeActionVisitor]
   * for all execute actions found, the [forEachActionVisitor] for all for-each
   * actions, and the [includeActionVisitor] for all include actions. Add all
   * errors into the given list of [results].
   */
  private fun visit(actions: List<Action>, results: MutableList<ValidationError>,
      executeActionVisitor: (ExecuteAction, List<String>) -> Unit = { _, _ -> },
      forEachActionVisitor: (ForEachAction, List<String>) -> Unit = { _, _ -> },
      includeActionVisitor: (IncludeAction, List<String>) -> Unit = { _, _ -> },
      path: List<String> = listOf(type.rootPath)) {
    for ((i, action) in actions.withIndex()) {
      when (action) {
        is ExecuteAction -> {
          val newPath = path + listOf("actions[$i](execute ${action.service})")
          executeActionVisitor(action, newPath)
        }

        is ForEachAction -> {
          val newPath = path + listOf("actions[$i](for-each)")
          forEachActionVisitor(action, newPath)
          visit(action.actions, results, executeActionVisitor,
              forEachActionVisitor, includeActionVisitor, newPath)
        }

        is IncludeAction -> {
          val newPath = path + listOf("actions[$i](include ${action.macro})")
          includeActionVisitor(action, newPath)
        }
      }
    }
  }

  /**
   * Validate that all output variables are undefined (i.e. that they don't
   * have values)
   */
  private fun outputsWithValues(actions: List<Action>,
      results: MutableList<ValidationError>) {
    val failedVariables = mutableSetOf<String>()

    fun visitOutputs(outputs : List<Parameter>, path: List<String>) {
      for ((i, output) in outputs.withIndex()) {
        if (output.variable.value != null && !failedVariables.contains(output.variable.id)) {
          failedVariables.add(output.variable.id)
          results.add(makeOutputWithValueError(output.variable, path + listOf("outputs[$i]")))
        }
      }
    }

    visit(
        actions,
        results,
        executeActionVisitor = { action, path ->
          visitOutputs(action.outputs, path)
        },
        forEachActionVisitor = { action, path ->
          if (action.output?.value != null && !failedVariables.contains(action.output.id)) {
            failedVariables.add(action.output.id)
            results.add(makeOutputWithValueError(action.output, path + listOf("output")))
          }
        },
        includeActionVisitor = { action, path ->
          visitOutputs(action.outputs, path)
        }
    )
  }

  private fun duplicateIds(vars: List<Variable>, actions: List<Action>,
      results: MutableList<ValidationError>) {
    val ids = mutableSetOf<String>()

    fun addId(id: String, path: List<String>) {
      if (ids.contains(id)) {
        results.add(makeDuplicateIdError(id, path))
      } else {
        ids.add(id)
      }
    }

    for ((i, v) in vars.withIndex()) {
      addId(v.id, listOf(type.rootPath, "vars[$i]"))
    }

    val visitor = { action: Action, path: List<String> ->
      addId(action.id, path)
    }

    visit(actions, results, executeActionVisitor = visitor,
        forEachActionVisitor = visitor, includeActionVisitor = visitor)
  }

  /**
   * Check that macro parameters don't have duplicate IDs
   */
  private fun duplicateParameterIds(parameters: List<MacroParameter>,
      results: MutableList<ValidationError>) {
    val ids = mutableSetOf<String>()
    for ((i, p) in parameters.withIndex()) {
      if (ids.contains(p.id)) {
        results.add(makeDuplicateParameterIdError(p.id,
            listOf(type.rootPath, "parameters[$i]")))
      } else {
        ids.add(p.id)
      }
    }
  }

  private fun missingDependsOnTargets(actions: List<Action>,
      results: MutableList<ValidationError>) {
    // collect all action IDs
    val ids = mutableSetOf<String>()
    val visitor = { action: Action, _: List<String> ->
      ids.add(action.id)
      Unit
    }
    visit(actions, results, executeActionVisitor = visitor,
        forEachActionVisitor = visitor, includeActionVisitor = visitor)

    val checkDependencies = { action: Action, path: List<String> ->
      for (d in action.dependsOn) {
        if (!ids.contains(d)) {
          results.add(makeMissingDependsOnTargetError(action.id, d, path))
        }
      }
    }

    // check dependencies
    visit(actions, results, executeActionVisitor = checkDependencies,
        forEachActionVisitor = checkDependencies,
        includeActionVisitor = checkDependencies)
  }

  private fun collectAllOutputs(actions: List<Action>): Set<String> {
    val outputIds = mutableSetOf<String>()
    val visitOutputs = { outputs: List<Parameter> ->
      outputIds.addAll(outputs.map { it.variable.id })
    }
    visit(
        actions,
        mutableListOf(),
        executeActionVisitor = { action, _ ->
          visitOutputs(action.outputs)
        },
        forEachActionVisitor = { action, _ ->
          action.output?.let { outputIds.add(it.id) }
          outputIds.add(action.enumerator.id)
        },
        includeActionVisitor = { action, _ ->
          visitOutputs(action.outputs)
        }
    )
    return outputIds
  }

  private fun missingInputValues(actions: List<Action>,
      macroInputs: Map<String, MacroParameter>,
      results: MutableList<ValidationError>) {
    val outputIds = collectAllOutputs(actions)

    // check if all inputs have values or if they refer to a macro parameter
    // or a known output
    val visitInputs = { inputs: List<InputParameter>, path: List<String> ->
      for ((i, input) in inputs.withIndex()) {
        if (
            input.variable.value == null &&
            !outputIds.contains(input.variable.id) &&
            !macroInputs.contains(input.variable.id)
        ) {
          results.add(makeMissingInputValueError(input.variable, path + listOf("inputs[$i]")))
        }
      }
    }
    visit(
        actions,
        results,
        executeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        },
        forEachActionVisitor = { action, path ->
          if (
              action.input.value == null &&
              !outputIds.contains(action.input.id) &&
              !macroInputs.contains(action.input.id)
          ) {
            results.add(makeMissingInputValueError(action.input, path + listOf("input")))
          }
        },
        includeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        }
    )
  }

  private fun reuseOutput(actions: List<Action>, results: MutableList<ValidationError>) {
    val outputIds = mutableSetOf<String>()

    val visitOutputs = { outputs: List<Parameter>, path: List<String> ->
      for ((i, o) in outputs.withIndex()) {
        if (outputIds.contains(o.variable.id)) {
          results.add(makeReuseOutputError(o.variable, path + listOf("outputs[$i]")))
        } else {
          outputIds.add(o.variable.id)
        }
      }
      outputIds.addAll(outputs.map { it.variable.id })
    }

    visit(
        actions,
        results,
        executeActionVisitor = { action, path ->
          visitOutputs(action.outputs, path)
        },
        forEachActionVisitor = { action, path ->
          if (action.output != null) {
            if (outputIds.contains(action.output.id)) {
              results.add(makeReuseOutputError(action.output, path + listOf("output")))
            } else {
              outputIds.add(action.output.id)
            }
          }
        },
        includeActionVisitor = { action, path ->
          visitOutputs(action.outputs, path)
        }
    )
  }

  private fun reuseEnumerator(actions: List<Action>, results: MutableList<ValidationError>) {
    val enumIds = mutableSetOf<String>()
    visit(
        actions,
        results,
        forEachActionVisitor = { action, path ->
          if (enumIds.contains(action.enumerator.id)) {
            results.add(makeReuseEnumeratorError(action.enumerator, path + listOf("enumerator")))
          } else {
            enumIds.add(action.enumerator.id)
          }
        }
    )
  }

  private fun enumeratorAsInput(actions: List<Action>,
      results: MutableList<ValidationError>) {
    // collect all outputs (except for enumerators)
    val outputIds = mutableSetOf<String>()
    val visitOutputs = { outputs: List<Parameter> ->
      outputIds.addAll(outputs.map { it.variable.id })
    }
    visit(
        actions,
        results,
        executeActionVisitor = { action, _ ->
          visitOutputs(action.outputs)
        },
        forEachActionVisitor = { action, _ ->
          action.output?.let { outputIds.add(it.id) }
        },
        includeActionVisitor = { action, _ ->
          visitOutputs(action.outputs)
        }
    )

    // check all enumerators
    visit(
        actions,
        results,
        forEachActionVisitor = { action, path ->
          if (outputIds.contains(action.enumerator.id)) {
            results.add(makeEnumeratorAsOutputError(action.enumerator, path + listOf("enumerator")))
          }
        }
    )
  }

  private fun scoping(vars: List<Variable>, actions: List<Action>,
      results: MutableList<ValidationError>) {
    // collect all possible outputs
    val outputIds = collectAllOutputs(actions)

    val stack = ArrayDeque<Set<String>>()

    fun isVisible(v: Variable) =
        stack.any { frame -> frame.contains(v.id) }

    fun visitScope(actions: List<Action>, path: List<String>) {
      // collect all outputs visible at this level
      val frame = mutableSetOf<String>()
      for (a in actions) {
        when (a) {
          is ExecuteAction -> a.outputs.forEach { frame.add(it.variable.id) }
          is ForEachAction -> a.output?.let { frame.add(it.id) }
          is IncludeAction -> a.outputs.forEach { frame.add(it.variable.id) }
        }
      }
      stack.add(frame)

      // check if all inputs are visible
      val visitInputs = { inputs: List<InputParameter>, newPath: List<String> ->
        for (input in inputs) {
          if (input.variable.value == null &&
              outputIds.contains(input.variable.id) &&
              !isVisible(input.variable)) {
            results.add(makeScopingError(input.variable, newPath))
          }
        }
      }
      for ((i, action) in actions.withIndex()) {
        when (action) {
          is ExecuteAction -> {
            val newPath = path + listOf("actions[$i](execute ${action.service})")
            visitInputs(action.inputs, newPath)
          }

          is ForEachAction -> {
            val newPath = path + listOf("actions[$i](for-each)")
            if (action.input.value == null &&
                outputIds.contains(action.input.id) &&
                !isVisible(action.input)) {
              results.add(makeScopingError(action.input, newPath))
            }

            // add enumerator to stack
            stack.add(setOf(action.enumerator.id))

            // check children
            visitScope(action.actions, newPath)

            stack.removeLast()
          }

          is IncludeAction -> {
            val newPath = path + listOf("actions[$i](include ${action.macro})")
            visitInputs(action.inputs, newPath)
          }
        }
      }

      stack.removeLast()
    }

    // add all workflow vars with values to stack
    stack.add(vars.filter { it.value != null }.map { it.id }.toSet())

    visitScope(actions, listOf(type.rootPath))
  }

  /**
   * A macro parameter must not be defined as a variable
   */
  private fun redeclaredParameter(vars: List<Variable>,
      parameters: List<MacroParameter>, results: MutableList<ValidationError>) {
    val pm = parameters.associateBy { it.id }
    for ((i, v) in vars.withIndex()) {
      if (pm.contains(v.id)) {
        results.add(makeRedeclaredParameterError(v,
            listOf(type.rootPath, "vars[$i]")))
      }
    }
  }

  /**
   * An input parameter of a macro must not have a value
   */
  private fun inputParameterWithValue(actions: List<Action>,
      inputs: Map<String, MacroParameter>,
      results: MutableList<ValidationError>) {
    fun visitInputs(actionInputs: List<InputParameter>, path: List<String>) {
      for ((i, input) in actionInputs.withIndex()) {
        when (input) {
          is AnonymousParameter -> {
            // nothing to do here
          }
          is GenericParameter -> {
            if (inputs.contains(input.variable.id) &&
                input.variable.value != null) {
              results.add(makeInputParameterWithValueError(input.variable,
                  path + "inputs[$i]"))
            }
          }
        }
      }
    }

    visit(
        actions,
        results,
        executeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        },
        forEachActionVisitor = { action, path ->
          if (inputs.contains(action.input.id) &&
              action.input.value != null) {
            results.add(makeInputParameterWithValueError(action.input, path + "input"))
          }
        },
        includeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        }
    )
  }

  /**
   * An input parameter of a macro must not be used as an output
   */
  private fun inputAsOutput(actions: List<Action>,
      inputs: Map<String, MacroParameter>,
      results: MutableList<ValidationError>) {
    visit(
        actions,
        results,
        executeActionVisitor = { action, path ->
          for ((i, output) in action.outputs.withIndex()) {
            if (inputs.contains(output.variable.id)) {
              results.add(makeInputAsOutputError(output.variable,
                  path + "outputs[$i]"))
            }
          }
        },
        forEachActionVisitor = { action, path ->
          if (action.output != null && inputs.contains(action.output.id)) {
            results.add(makeInputAsOutputError(action.output, path + "output"))
          }
        },
        includeActionVisitor = { action, path ->
          for ((i, output) in action.outputs.withIndex()) {
            if (inputs.contains(output.variable.id)) {
              results.add(makeInputAsOutputError(output.variable,
                  path + "outputs[$i]"))
            }
          }
        }
    )
  }

  /**
   * An output parameter of a macro must not be used as an input
   */
  private fun outputAsInput(actions: List<Action>,
      outputs: Map<String, MacroParameter>,
      results: MutableList<ValidationError>) {
    fun visitInputs(actionInputs: List<InputParameter>, path: List<String>) {
      for ((i, input) in actionInputs.withIndex()) {
        when (input) {
          is AnonymousParameter -> {
            // nothing to do
          }
          is GenericParameter -> {
            if (outputs.contains(input.variable.id)) {
              results.add(makeOutputAsInputError(input.variable,
                  path + "inputs[$i]"))
            }
          }
        }
      }
    }

    visit(
        actions,
        results,
        executeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        },
        forEachActionVisitor = { action, path ->
          if (outputs.contains(action.input.id)) {
            results.add(makeOutputAsInputError(action.input, path + "input"))
          }
        },
        includeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        }
    )
  }

  /**
   * A macro parameter must not be used as a for-each action's enumerator
   */
  private fun parameterAsEnumerator(actions: List<Action>,
      parameters: List<MacroParameter>, results: MutableList<ValidationError>) {
    val pm = parameters.associateBy { it.id }
    visit(
        actions,
        results,
        forEachActionVisitor = { action, path ->
          if (pm.contains(action.enumerator.id)) {
            results.add(makeParameterAsEnumeratorError(action.enumerator,
                path + "enumerator"))
          }
        }
    )
  }

  private fun makeOutputWithValueError(v: Variable, path: List<String>) = ValidationError(
      "Output variable `${v.id}' has a value.", "Output variables should " +
      "always be undefined as their value will be generated during runtime. " +
      "If you want to put the output into a specific directory, use the " +
      "`prefix' attribute of the execute action's output parameter instead.", path)

  private fun makeDuplicateIdError(id: String, path: List<String>) = ValidationError(
      "Duplicate identifier `$id'.", "Identifiers of both variables and " +
      "actions must be unique and cannot overlap.", path)

  private fun makeDuplicateParameterIdError(id: String, path: List<String>) = ValidationError(
      "Duplicate parameter identifier `$id'.", "Identifiers of macro " +
      "parameters must be unique.", path)

  private fun makeMissingDependsOnTargetError(actionId: String,
        target: String, path: List<String>) = ValidationError(
      "Unable to resolve action dependency `$actionId'->`$target'.", "Action " +
      "`$actionId' depends on an action with ID `$target' but this action does " +
      "not exist the workflow.", path)

  private fun makeMissingInputValueError(v: Variable, path: List<String>) = ValidationError(
      "Input variable `${v.id}' has no value.", "The input variable has no " +
      "value and will never get one during workflow execution. Either define " +
      "a constant `value' or define an output variable with ID `${v.id}' " +
      "elsewhere in the workflow.", path)

  private fun makeReuseOutputError(v: Variable, path: List<String>) = ValidationError(
      "Output variable `${v.id}' used more than once.", "A variable can only " +
      "be used once as an output. Introduce a new variable for each output.",
      path)

  private fun makeReuseEnumeratorError(v: Variable, path: List<String>) = ValidationError(
      "Enumerator `${v.id}' used more than once.", "A variable can only " +
      "be used once as an enumerator of a for-each action.", path)

  private fun makeEnumeratorAsOutputError(v: Variable, path: List<String>) = ValidationError(
      "Enumerator `${v.id}' used as an output.", "An enumerator may only be " +
      "used as an input.", path)

  private fun makeScopingError(v: Variable, path: List<String>) = ValidationError(
      "Variable `${v.id}' not visible.", "The value of variable `${v.id}' is " +
      "only visible inside the for-each action where the variable has been " +
      "defined as an output or an enumerator. If you want to access the value " +
      "outside the for-each action, use `yieldToOutput'.", path)

  private fun makeRedeclaredParameterError(v: Variable, path: List<String>) = ValidationError(
      "Variable `${v.id}' already declared as macro parameter.",
      "Macro parameters are implicit variables. You must not define them " +
      "again in the macro's list of variables.", path)

  private fun makeInputParameterWithValueError(v: Variable, path: List<String>) = ValidationError(
      "Variable `${v.id}' has a value.", "The variable refers to a macro " +
      "input parameter and must not have a value. Its actual value will be " +
      "determined when the macro is included in a workflow.", path)

  private fun makeInputAsOutputError(v: Variable, path: List<String>) = ValidationError(
      "Input parameter `${v.id}' used as an output.", "A macro input " +
      "parameter may only be used as an input.", path)

  private fun makeOutputAsInputError(v: Variable, path: List<String>) = ValidationError(
      "Output parameter `${v.id}' used as an input.", "A macro output " +
      "parameter may only be used as an output.", path)

  private fun makeParameterAsEnumeratorError(v: Variable, path: List<String>) = ValidationError(
      "Macro parameter `${v.id}' used as an enumerator.", "Macro parameters " +
      "are either inputs or outputs but not enumerators.", path)
}
