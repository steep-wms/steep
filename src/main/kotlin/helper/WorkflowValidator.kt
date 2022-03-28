package helper

import model.workflow.Action
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
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
  data class ValidationError(val message: String, val details: String? = null)

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
    return results
  }

  /**
   * Recursively visit a list of [actions]. Call the given [executeActionVisitor]
   * for all execute actions found, and the [forEachActionVisitor] for all
   * for-each actions. Add all errors into the given list of [results].
   */
  private fun visit(actions: List<Action>, results: MutableList<ValidationError>,
      executeActionVisitor: (ExecuteAction) -> Unit = {},
      forEachActionVisitor: (ForEachAction) -> Unit = {}) {
    for (action in actions) {
      when (action) {
        is ExecuteAction -> executeActionVisitor(action)

        is ForEachAction -> {
          forEachActionVisitor(action)
          visit(action.actions, results, executeActionVisitor, forEachActionVisitor)
        }

        else -> results.add(ValidationError("Unknown action type: " +
            "`${action::class.java.name}'"))
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

    visit(workflow.actions, results, executeActionVisitor = { action ->
      for (output in action.outputs) {
        if (output.variable.value != null && !failedVariables.contains(output.variable.id)) {
          failedVariables.add(output.variable.id)
          results.add(makeOutputWithValueError(output.variable))
        }
      }
    }, forEachActionVisitor = { action ->
      if (action.output?.value != null && !failedVariables.contains(action.output.id)) {
        failedVariables.add(action.output.id)
        results.add(makeOutputWithValueError(action.output))
      }
    })
  }

  private fun duplicateIds(workflow: Workflow, results: MutableList<ValidationError>) {
    val ids = mutableSetOf<String>()

    fun addId(id: String) {
      if (ids.contains(id)) {
        results.add(makeDuplicateIdError(id))
      } else {
        ids.add(id)
      }
    }

    for (v in workflow.vars) {
      addId(v.id)
    }

    visit(workflow.actions, results, executeActionVisitor = { action ->
      addId(action.id)
    }, forEachActionVisitor = { action ->
      addId(action.id)
    })
  }

  private fun missingDependsOnTargets(workflow: Workflow,
      results: MutableList<ValidationError>) {
    // collect all action IDs
    val ids = mutableSetOf<String>()
    visit(workflow.actions, results, executeActionVisitor = { action ->
      ids.add(action.id)
    }, forEachActionVisitor = { action ->
      ids.add(action.id)
    })

    fun checkDependencies(actionId: String, deps: List<String>) {
      for (d in deps) {
        if (!ids.contains(d)) {
          results.add(makeMissingDependsOnTargetError(actionId, d))
        }
      }
    }

    // check dependencies
    visit(workflow.actions, results, executeActionVisitor = { action ->
      checkDependencies(action.id, action.dependsOn)
    }, forEachActionVisitor = { action ->
      checkDependencies(action.id, action.dependsOn)
    })
  }

  private fun missingInputValues(workflow: Workflow,
      results: MutableList<ValidationError>) {
    // collect all outputs
    val outputIds = mutableSetOf<String>()
    visit(workflow.actions, results, executeActionVisitor = { action ->
      outputIds.addAll(action.outputs.map { it.variable.id })
    }, forEachActionVisitor = { action ->
      action.output?.let { outputIds.add(it.id) }
      outputIds.add(action.enumerator.id)
    })

    // check if all inputs have values or if they refer to a known output
    visit(workflow.actions, results, executeActionVisitor = { action ->
      for (i in action.inputs) {
        if (i.variable.value == null && !outputIds.contains(i.variable.id)) {
          results.add(makeMissingInputValueError(i.variable))
        }
      }
    }, forEachActionVisitor = { action ->
      if (action.input.value == null && !outputIds.contains(action.input.id)) {
        results.add(makeMissingInputValueError(action.input))
      }
    })
  }

  private fun makeOutputWithValueError(v: Variable) = ValidationError(
      "Output variable `${v.id}' has a value.", "Output variables should " +
      "always be undefined as their value will be generated during runtime. " +
      "If you want to put the output into a specific directory, use the " +
      "`prefix' attribute of the execute action's output parameter instead.")

  private fun makeDuplicateIdError(id: String) = ValidationError(
      "Duplicate identifier `$id'.", "Identifiers of both variables and " +
      "actions must be unique and cannot overlap.")

  private fun makeMissingDependsOnTargetError(actionId: String, target: String) = ValidationError(
      "Unable to resolve action dependency `$actionId'->`$target'.", "Action " +
      "`$actionId' depends on an action with ID `$target' but this action does " +
      "not exist the workflow."
  )

  private fun makeMissingInputValueError(v: Variable) = ValidationError(
      "Input variable `${v.id}' has no value.", "The input variable has no " +
      "value and will never get one during workflow execution. Either define " +
      "a constant `value' or define an output variable with ID `${v.id}' " +
      "elsewhere in the workflow."
  )
}
