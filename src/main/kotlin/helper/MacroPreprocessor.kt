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
   * Maps macro-internal variables to renamed variables whose names are
   * based on the given [macro]'s ID and the given [includeActionId].
   */
  private class VariableRenames(private val macro: Macro,
      private val includeActionId: String) {
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
        val newId = makeNewId(includeActionId, macro, v.id)
        v.copy(id = newId)
      }
    }
  }

  private fun makeNewId(includeActionId: String, macro: Macro, id: String) =
    "$${includeActionId.trimStart('$')}$${macro.id}$$id"

  /**
   * Preprocess a workflow using the given list of [macros]. Process all
   * include actions and replace them with the macro bodies. Map variables
   * inside included macros to workflow variables, rename internal variables
   * and action IDs.
   */
  fun preprocess(workflow: Workflow, macros: Map<String, Macro>): Workflow {
    val (newActions, newVariables) = preprocess(workflow.actions, macros)
    if (newActions === workflow.actions) {
      return workflow
    }

    // collect all action IDs
    val allActionIds = mutableSetOf<String>()
    collectActionIds(newActions, allActionIds)

    // update dependencies if necessary
    val updatedActions = updateDependencies(newActions, allActionIds)

    return workflow.copy(
        vars = workflow.vars + newVariables,
        actions = updatedActions
    )
  }

  /**
   * Preprocess a list of [actions] using the given list of [macros]
   */
  private fun preprocess(actions: List<Action>,
      macros: Map<String, Macro>): Pair<List<Action>, List<Variable>> {
    val popInclude = IncludeAction(macro = "")

    val queue = ArrayDeque(actions)
    val newActions = mutableListOf<Action>()
    val newVars = mutableListOf<Variable>()
    val includedMacros = ArrayDeque<String>()
    var changed = false

    while (queue.isNotEmpty()) {
      when (val a = queue.removeFirst()) {
        is ExecuteAction -> newActions.add(a)

        is ForEachAction -> {
          val (newSubActions, newSubVars) = preprocess(a.actions, macros)
          if (newSubActions === a.actions && newSubVars.isEmpty()) {
            newActions.add(a)
          } else {
            newActions.add(a.copy(actions = newSubActions))
            newVars.addAll(newSubVars)
            changed = true
          }
        }

        is IncludeAction -> {
          if (a === popInclude) {
            includedMacros.removeLast()
          } else {
            if (includedMacros.contains(a.macro)) {
              throw IllegalArgumentException("Detected include cycle: " +
                  "${includedMacros.joinToString("->")}->${a.macro}")
            }

            val m = macros[a.macro] ?: throw IllegalArgumentException(
                "Unable to find macro `${a.macro}'")
            val (newSubActions, newSubVars) = expandMacro(a, m)

            // store included macro ID on stack and add an artificial action
            // that will later allow us to remove the ID from the stack again
            includedMacros.addLast(a.macro)
            queue.addFirst(popInclude)

            // add all expanded actions to the front of the queue
            newSubActions.asReversed().forEach { queue.addFirst(it) }
            newVars.addAll(newSubVars)
            changed = true
          }
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
   */
  private fun expandMacro(includeAction: IncludeAction,
      macro: Macro): Pair<List<Action>, List<Variable>> {
    // collect inputs and outputs
    val (inputs, outputs) = collectIncludeParameters(includeAction, macro)

    val renames = VariableRenames(macro, includeAction.id)

    // rename variables
    val renamedVars = macro.vars.map { renames.rename(it) }

    // rename IDs and variables in actions
    val actions = expandActions(macro.actions, macro, inputs, outputs,
        renames, includeAction.id, includeAction.dependsOn)

    return actions to renamedVars
  }

  /**
   * Collect all inputs and outputs of an [includeAction] for a given [macro]
   * and generate default inputs if necessary
   */
  private fun collectIncludeParameters(includeAction: IncludeAction,
      macro: Macro): Pair<Map<String, InputParameter>, Map<String, IncludeOutputParameter>> {
    val inputs = mutableMapOf<String, InputParameter>()
    val outputs = mutableMapOf<String, IncludeOutputParameter>()
    for (macroParam in macro.parameters) {
      when (macroParam.type) {
        Argument.Type.INPUT -> {
          var inputParam = includeAction.inputs.find { it.id == macroParam.id }

          // if there are no inputs but macroParam has a default value, add a
          // new generic input parameter
          if (inputParam == null && macroParam.default != null) {
            val newId = makeNewId(includeAction.id, macro, macroParam.id)
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
   * variable [renames], and [includeActionId]. Recursively map variables to
   * the given [inputs] and [outputs]. Add the given [dependencies] to all
   * expanded actions.
   */
  private fun expandActions(actions: List<Action>, macro: Macro,
      inputs: Map<String, InputParameter>, outputs: Map<String, IncludeOutputParameter>,
      renames: VariableRenames, includeActionId: String,
      dependencies: List<String>): MutableList<Action> {
    fun renameDependsOn(a: Action) =
        a.dependsOn.map { makeNewId(includeActionId, macro, it) }

    val result = mutableListOf<Action>()
    for (a in actions) {
      val newId = makeNewId(includeActionId, macro, a.id)
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
                  outputs = actionOutputs,
                  dependsOn = renameDependsOn(a) + dependencies
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
                  actions = expandActions(a.actions, macro, inputs, outputs,
                      renames, includeActionId, dependencies),
                  dependsOn = renameDependsOn(a) + dependencies
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
                  outputs = actionOutputs,
                  dependsOn = renameDependsOn(a) + dependencies
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

  /**
   * Recursively collect the IDs of all given [actions] in a [result] set
   */
  private fun collectActionIds(actions: List<Action>, result: MutableSet<String>) {
    for (a in actions) {
      when (a) {
        is ExecuteAction -> result.add(a.id)
        is ForEachAction -> {
          result.add(a.id)
          collectActionIds(a.actions, result)
        }
        is IncludeAction -> result.add(a.id)
      }
    }
  }

  /**
   * Recursively iterate through all given [actions] and update their
   * dependencies based on [allActionIds]. A dependency is replaced by all
   * action IDs whose prefixes match it. If the actions do not have
   * dependencies or if nothing needs to be replaced, this function returns
   * the original list of actions.
   */
  private fun updateDependencies(actions: List<Action>,
      allActionIds: Set<String>): List<Action> {
    val newActions = mutableListOf<Action>()
    var changed = false

    fun update(deps: List<String>): List<String> {
      val newDeps = mutableListOf<String>()
      var depsChanged = false
      for (d in deps) {
        if (allActionIds.contains(d)) {
          newDeps.add(d)
        } else {
          val newActionIds = allActionIds.filter { it.startsWith("$${d.trimStart('$')}$") }
          if (newActionIds.isEmpty()) {
            // This might be a user-error. Forward the dependency and let
            // the ProcessChainGenerator handle the error later.
            newDeps.add(d)
          } else {
            newDeps.addAll(newActionIds)
            depsChanged = true
          }
        }
      }
      return if (depsChanged) newDeps else deps
    }

    for (a in actions) {
      when (a) {
        is ExecuteAction -> {
          val newDeps = update(a.dependsOn)
          newActions.add(
              if (newDeps !== a.dependsOn) {
                changed = true
                a.copy(dependsOn = newDeps)
              } else {
                a
              }
          )
        }

        is ForEachAction -> {
          val newDeps = update(a.dependsOn)
          val newSubactions = updateDependencies(a.actions, allActionIds)
          newActions.add(
              if (newDeps !== a.dependsOn || newSubactions !== a.actions) {
                changed = true
                a.copy(dependsOn = newDeps, actions = newSubactions)
              } else {
                a
              }
          )
        }

        is IncludeAction -> {
          // there cannot be an IncludeAction any more at this point!
          throw RuntimeException("Still found an IncludeAction although " +
              "workflow was already preprocessed")
        }
      }
    }

    return if (changed) {
      newActions
    } else {
      actions
    }
  }
}
