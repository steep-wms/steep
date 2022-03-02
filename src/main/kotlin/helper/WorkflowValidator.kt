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

  private fun makeOutputWithValueError(v: Variable) = ValidationError(
      "Output variable `${v.id}' has a value.", "Output variables should " +
      "always be undefined as their value will be generated during runtime. " +
      "If you want to put the output into a specific directory, use the " +
      "`prefix' attribute of the execute action's output parameter instead.")

  private fun makeDuplicateIdError(id: String) = ValidationError(
      "Duplicate identifier `$id'.", "Identifiers of both variables and " +
      "actions must be unique and cannot overlap.")
}
