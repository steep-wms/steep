package helper

import model.workflow.Action
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.IncludeAction
import model.workflow.InputParameter
import model.workflow.Parameter
import model.workflow.Variable
import model.workflow.Workflow

/**
 * Validates workflows and checks for common mistakes
 * @author Michel Kraemer
 */
object WorkflowValidator {
  /**
   * The result of a failed validation. It has a human-readable [message] and
   * optional a string with [details] about the error and how it can be fixed.
   */
  data class ValidationError(val message: String, val details: String? = null,
      val path: List<String>)

  /**
   * Validate a [workflow]. Return a list of errors. This list will be empty
   * if the workflow is OK and no errors were found.
   */
  fun validate(workflow: Workflow): List<ValidationError> {
    val results = mutableListOf<ValidationError>()
    outputsWithValues(workflow, results)
    duplicateIds(workflow, results)
    missingDependsOnTargets(workflow, results)
    missingInputValues(workflow, results)
    reuseOutput(workflow, results)
    reuseEnumerator(workflow, results)
    enumeratorAsInput(workflow, results)
    scoping(workflow, results)
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
      path: List<String> = listOf("workflow")) {
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
  private fun outputsWithValues(workflow: Workflow,
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
        workflow.actions,
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

  private fun duplicateIds(workflow: Workflow, results: MutableList<ValidationError>) {
    val ids = mutableSetOf<String>()

    fun addId(id: String, path: List<String>) {
      if (ids.contains(id)) {
        results.add(makeDuplicateIdError(id, path))
      } else {
        ids.add(id)
      }
    }

    for ((i, v) in workflow.vars.withIndex()) {
      addId(v.id, listOf("workflow", "vars[$i]"))
    }

    val visitor = { action: Action, path: List<String> ->
      addId(action.id, path)
    }

    visit(workflow.actions, results, executeActionVisitor = visitor,
        forEachActionVisitor = visitor, includeActionVisitor = visitor)
  }

  private fun missingDependsOnTargets(workflow: Workflow,
      results: MutableList<ValidationError>) {
    // collect all action IDs
    val ids = mutableSetOf<String>()
    val visitor = { action: Action, _: List<String> ->
      ids.add(action.id)
      Unit
    }
    visit(workflow.actions, results, executeActionVisitor = visitor,
        forEachActionVisitor = visitor, includeActionVisitor = visitor)

    val checkDependencies = { action: Action, path: List<String> ->
      for (d in action.dependsOn) {
        if (!ids.contains(d)) {
          results.add(makeMissingDependsOnTargetError(action.id, d, path))
        }
      }
    }

    // check dependencies
    visit(workflow.actions, results, executeActionVisitor = checkDependencies,
        forEachActionVisitor = checkDependencies,
        includeActionVisitor = checkDependencies)
  }

  private fun collectAllOutputs(workflow: Workflow): Set<String> {
    val outputIds = mutableSetOf<String>()
    val visitOutputs = { outputs: List<Parameter> ->
      outputIds.addAll(outputs.map { it.variable.id })
    }
    visit(
        workflow.actions,
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

  private fun missingInputValues(workflow: Workflow,
      results: MutableList<ValidationError>) {
    val outputIds = collectAllOutputs(workflow)

    // check if all inputs have values or if they refer to a known output
    val visitInputs = { inputs: List<InputParameter>, path: List<String> ->
      for ((i, input) in inputs.withIndex()) {
        if (input.variable.value == null && !outputIds.contains(input.variable.id)) {
          results.add(makeMissingInputValueError(input.variable, path + listOf("inputs[$i]")))
        }
      }
    }
    visit(
        workflow.actions,
        results,
        executeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        },
        forEachActionVisitor = { action, path ->
          if (action.input.value == null && !outputIds.contains(action.input.id)) {
            results.add(makeMissingInputValueError(action.input, path + listOf("input")))
          }
        },
        includeActionVisitor = { action, path ->
          visitInputs(action.inputs, path)
        }
    )
  }

  private fun reuseOutput(workflow: Workflow, results: MutableList<ValidationError>) {
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
        workflow.actions,
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

  private fun reuseEnumerator(workflow: Workflow, results: MutableList<ValidationError>) {
    val enumIds = mutableSetOf<String>()
    visit(
        workflow.actions,
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

  private fun enumeratorAsInput(workflow: Workflow, results: MutableList<ValidationError>) {
    // collect all outputs (except for enumerators)
    val outputIds = mutableSetOf<String>()
    val visitOutputs = { outputs: List<Parameter> ->
      outputIds.addAll(outputs.map { it.variable.id })
    }
    visit(
        workflow.actions,
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
        workflow.actions,
        results,
        forEachActionVisitor = { action, path ->
          if (outputIds.contains(action.enumerator.id)) {
            results.add(makeEnumeratorAsOutputError(action.enumerator, path + listOf("enumerator")))
          }
        }
    )
  }

  private fun scoping(workflow: Workflow, results: MutableList<ValidationError>) {
    // collect all possible outputs
    val outputIds = collectAllOutputs(workflow)

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
    stack.add(workflow.vars.filter { it.value != null }.map { it.id }.toSet())

    visitScope(workflow.actions, listOf("workflow"))
  }

  private fun makeOutputWithValueError(v: Variable, path: List<String>) = ValidationError(
      "Output variable `${v.id}' has a value.", "Output variables should " +
      "always be undefined as their value will be generated during runtime. " +
      "If you want to put the output into a specific directory, use the " +
      "`prefix' attribute of the execute action's output parameter instead.", path)

  private fun makeDuplicateIdError(id: String, path: List<String>) = ValidationError(
      "Duplicate identifier `$id'.", "Identifiers of both variables and " +
      "actions must be unique and cannot overlap.", path)

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
}
