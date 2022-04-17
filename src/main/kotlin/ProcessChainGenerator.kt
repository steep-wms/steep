import db.PluginRegistryFactory
import helper.IDGenerator
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.json.JsonObject
import model.metadata.RuntimeArgument
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument
import model.processchain.Argument.Type.INPUT
import model.processchain.Argument.Type.OUTPUT
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.Action
import model.workflow.AnonymousParameter
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.GenericParameter
import model.workflow.OutputParameter
import model.workflow.Variable
import model.workflow.Workflow
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Generates process chains from a workflow
 * @param workflow the workflow to convert to process chains
 * @param tmpPath a directory where temporary workflow results should be stored
 * @param outPath a directory where final workflow results should be stored
 * @param services service metadata
 */
class ProcessChainGenerator(workflow: Workflow, private val tmpPath: String,
    private val outPath: String, private val services: List<Service>,
    private val idGenerator: IDGenerator = UniqueID) {
  companion object {
    private val log = LoggerFactory.getLogger(ProcessChainGenerator::class.java)
    private const val TMP_OUTPUT_SUFFIX = "$$"
  }

  private val vars = workflow.vars.toMutableList()
  private val actions = workflow.actions.toMutableSet()
  private val variableValues = mutableMapOf<String, Any>()
  private val executedActionIds = mutableSetOf<String>()
  private val forEachOutputsToBeCollected = mutableMapOf<String, List<Variable>>()
  private val forEachOutputsReadyToBeRenamed = mutableSetOf<String>()
  private val forEachSubActionsToWaitFor = mutableMapOf<String, Set<String>>()
  private val iterations = mutableMapOf<String, Int>()
  private val pluginRegistry = PluginRegistryFactory.create()

  /**
   * Returns `true` if the workflow has been fully converted to process chains
   */
  fun isFinished() = actions.isEmpty()

  /**
   * The priority to assign to generated process chains
   */
  var defaultPriority = workflow.priority

  /**
   * Create the next set of process chains. Call this method until it returns
   * an empty list (i.e. until it does not produce more process chains).
   * Execute the process chains after each call to this method and pass their
   * [results] to the next call. Also pass the [executedExecutableIds] of any
   * [Executable] that was executed successfully so dependencies between
   * [Action]s can be resolved correctly.
   */
  fun generate(results: Map<String, List<Any>>? = null,
      executedExecutableIds: Set<String>? = null): List<ProcessChain> {
    // replace variable values with results
    results?.forEach { (key, value) ->
      variableValues[key] = if (value.size == 1) value[0] else value
    }

    // save IDs of already executed actions
    executedExecutableIds?.let { executedActionIds.addAll(it) }

    // collect for-each outputs
    do {
      var didCollectOutputs = false
      val newForEachOutputsToBeCollected = mutableMapOf<String, List<Variable>>()
      val i = forEachOutputsToBeCollected.iterator()
      for ((outputId, outputsToCollect) in i) {
        val toKeep = mutableListOf<Variable>()

        // try to get values of all outputs
        // separate them from those that don't have values yet
        val collectedOutputs = mutableListOf<Any>()
        for (o in outputsToCollect) {
          val v = o.value ?: variableValues[o.id]
          if (v != null) {
            collectedOutputs.add(v)
          } else {
            toKeep.add(o)
          }
        }

        if (collectedOutputs.isNotEmpty()) {
          variableValues[outputId] = yieldTo(variableValues[outputId], collectedOutputs)

          // repeat until no outputs were collected anymore
          didCollectOutputs = true
        }

        if (toKeep.isEmpty()) {
          // if all values are available, remove the item from `forEachOutputsToBeCollected`
          i.remove()

          // if `outputId` is a temporary variable name and the for-each action
          // will not produce more values, rename the temporary variable so
          // subsequent actions can be triggered
          if (forEachOutputsReadyToBeRenamed.contains(outputId)) {
            renameTemporaryOutput(outputId)
            forEachOutputsReadyToBeRenamed.remove(outputId)
          }
        } else {
          // otherwise, wait for the rest
          newForEachOutputsToBeCollected[outputId] = toKeep
        }
      }

      // Overwrite entries in forEachOutputsToBeCollected with the remaining
      // outputs to wait for. This basically removes those outputs we've
      // already collected.
      forEachOutputsToBeCollected.putAll(newForEachOutputsToBeCollected)
    } while (didCollectOutputs)

    // mark for-each actions as executed when all sub-actions have been executed
    do {
      var didMark = false
      val i = forEachSubActionsToWaitFor.iterator()
      for ((forEachActionId, actionsToWaitFor) in i) {
        if (executedActionIds.containsAll(actionsToWaitFor)) {
          // mark for-each action as executed
          executedActionIds.add(forEachActionId)
          i.remove()

          // repeat until no for-each actions have been marked anymore
          didMark = true
        }
      }
    } while (didMark)

    unrollForEachActions()
    return createProcessChains()
  }

  /**
   * Recursively unroll [ForEachAction]s whose inputs are all available.
   * Unrolling means copying the sub-actions `n` times (once for each
   * iteration) and then removing the [ForEachAction]. The method also takes
   * care of avoiding collisions between identifiers of copied variables.
   */
  private fun unrollForEachActions() {
    val foreachActions = ArrayDeque(actions.filterIsInstance<ForEachAction>())

    while (foreachActions.isNotEmpty()) {
      val action = foreachActions.removeFirst()
      val enumId = action.enumerator.id

      // only unroll if dependencies are met
      if (action.dependsOn.any { !executedActionIds.contains(it) }) {
        continue
      }

      // get value of enumerator
      val enumInput = "${action.input.id}$$enumId" // unique for this action
      val enumPosition = variableValues[enumInput] as Int? ?: 0

      // get inputs if they are available, otherwise continue
      val recursiveInput = "${action.input.id}$$enumId\$rec" // unique for this action
      val tempInput = variableValues[action.input.id + TMP_OUTPUT_SUFFIX]
      val inputCollection = (tempInput ?: variableValues[action.input.id] ?: action.input.value)?.let { input ->
        val il = when (input) {
          is List<*> -> input
          is Collection<*> -> input.toList()
          else -> listOf(input)
        }
        variableValues[enumInput] = il.size
        il.subList(enumPosition, il.size)
      }.let { inputs ->
        // add recursive input
        val r = variableValues[recursiveInput] as List<Any?>?
        if (inputs != null && r != null) {
          inputs + r
        } else {
          inputs ?: r
        }
      } ?: continue

      // unroll for-each action
      val yieldToOutputs = mutableListOf<Variable>()
      val yieldToInputs = mutableListOf<Variable>()
      val unrolledSubActionIds = mutableSetOf<String>()
      for (enumValue in inputCollection) {
        // since the for-each action might be recursive, we must calculate a
        // global index of all iterations instead of just using
        // `inputCollection.withIndex()`
        val iteration = nextIteration(enumId)

        // for each iteration, generate new identifier for enumerator
        val iid = "$enumId$$iteration"
        val substitutions = mutableMapOf(enumId to Variable(iid, enumValue))

        // copy sub-actions and append them to `actions`
        // do this in two passes so we can handle dependencies between
        // actions in reverse order
        val firstPassActions = action.actions.map { unrollAction(it, substitutions, iteration, true) }
        for (subAction in firstPassActions) {
          val unrolledSubAction = unrollAction(subAction, substitutions, iteration, false)
          actions.add(unrolledSubAction)

          // recursively unroll for-each actions
          if (unrolledSubAction is ForEachAction) {
            foreachActions.add(unrolledSubAction)
          }

          unrolledSubActionIds.add(unrolledSubAction.id)
        }

        // collect yielded output
        if (action.yieldToOutput != null) {
          val subst = substitutions[action.yieldToOutput.id] ?: throw IllegalStateException(
              "Cannot yield non-existing variable `${action.yieldToOutput.id}'")
          yieldToOutputs.add(subst)
        }
        if (action.yieldToInput != null) {
          val subst = substitutions[action.yieldToInput.id] ?: throw IllegalStateException(
              "Cannot yield non-existing variable `${action.yieldToInput.id}'")
          yieldToInputs.add(subst)
        }
      }

      // keep track of outputs that need to be collected. rename output
      // variable to avoid triggering subsequent actions until all outputs
      // have been collected.
      if (action.output != null) {
        val outputId = action.output.id + TMP_OUTPUT_SUFFIX
        val oldList = forEachOutputsToBeCollected[outputId] ?: emptyList()
        forEachOutputsToBeCollected[outputId] = oldList + yieldToOutputs
      }

      // keep track of IDs of unrolled sub-actions so we can later mark the
      // for-each action as executed when all sub-actions have been executed
      forEachSubActionsToWaitFor.compute(action.id) { _, oldIds ->
        if (oldIds == null) {
          unrolledSubActionIds
        } else {
          oldIds + unrolledSubActionIds
        }
      }

      if (yieldToInputs.isEmpty()) {
        if (forEachOutputsToBeCollected[recursiveInput]?.isNotEmpty() == true) {
          // keep for-each action until all cloned actions that could yield
          // to input have been executed (in other words, if we are not waiting
          // for outputs any more that should be yielded to input)
          continue
        }
        if (tempInput != null) {
          // We've fetched our input from a temporary variable. Since more
          // values might come in later, we have to keep the for-each action.
          continue
        }

        // remove unrolled for-each action
        actions.remove(action)

        if (action.output != null) {
          // Up to this point, the output has a temporary name (with the suffix
          // `TMP_OUTPUT_SUFFIX`).
          val outputId = action.output.id + TMP_OUTPUT_SUFFIX
          if (forEachOutputsToBeCollected[outputId]?.isNotEmpty() == true) {
            // We are still waiting for outputs of sub-actions. Mark the output
            // as ready to be renamed to its original name as soon as all these
            // outputs have been collected.
            forEachOutputsReadyToBeRenamed.add(outputId)
          } else {
            // shortcut: since we are not waiting for any more outputs of
            // sub-actions, we can rename the output right away
            renameTemporaryOutput(outputId)
          }
        }

        if (action.yieldToInput != null) {
          forEachOutputsToBeCollected.remove(recursiveInput)
        }

        // now that the action has been removed, we don't need enum position
        // and recursive input anymore
        variableValues.remove(enumInput)
        variableValues.remove(recursiveInput)
      } else {
        // keep the for-each action for the next iteration but set temporary
        // input variable to an empty list, so we can yield into it
        variableValues[recursiveInput] = emptyList<String>()
        val oldList = forEachOutputsToBeCollected[recursiveInput] ?: emptyList()
        forEachOutputsToBeCollected[recursiveInput] = oldList + yieldToInputs
      }
    }
  }

  /**
   * Recursively unroll a given [action] by appending the current [iteration] to
   * its ID and outputs (or enumerator if it's a for-each action) and replacing
   * variables and `dependsOn` targets with the given [substitutions]. If
   * [firstPass] is `true`, only replaces IDs, outputs, and enumerators. If
   * [firstPass] is `false`, only replaces inputs and `dependsOn` targets.
   */
  private fun unrollAction(action: Action, substitutions: MutableMap<String, Variable>,
      iteration: Int, firstPass: Boolean): Action {
    return when (action) {
      is ExecuteAction -> {
        if (firstPass) {
          action.copy(
            id = unrollId(action.id, substitutions, iteration),
            outputs = action.outputs.map {
              it.copy(variable = unrollVariable(it.variable, substitutions, iteration))
            }
          )
        } else {
          action.copy(
            inputs = action.inputs.map { input ->
              when (input) {
                is GenericParameter ->
                  input.copy(variable = substitutions[input.variable.id] ?: input.variable)
                is AnonymousParameter ->
                  input
              }
            },
            dependsOn = action.dependsOn.map {
              substitutions[it]?.id ?: it
            }
          )
        }
      }

      is ForEachAction -> {
        if (firstPass) {
          val newId = unrollId(action.id, substitutions, iteration)
          val newEnum = unrollVariable(action.enumerator, substitutions, iteration)
          val newOutput = action.output?.let { unrollVariable(it, substitutions, iteration) }
          val newActions = action.actions.map { unrollAction(it, substitutions, iteration, true) }
          action.copy(
            id = newId,
            enumerator = newEnum,
            output = newOutput,
            actions = newActions
          )
        } else {
          val newActions = action.actions.map { unrollAction(it, substitutions, iteration, false) }
          action.copy(
            input = substitutions[action.input.id] ?: action.input,
            actions = newActions,
            yieldToOutput = action.yieldToOutput?.let { substitutions[it.id] ?: it },
            dependsOn = action.dependsOn.map { substitutions[it]?.id ?: it }
          )
        }
      }
    }
  }

  /**
   * Unroll an [id] for a given [iteration]. Renames the [id] and puts the old
   * name and a variable with the new one into the given map of [substitutions].
   */
  private fun unrollId(id: String, substitutions: MutableMap<String, Variable>,
    iteration: Int): String {
    return unrollVariable(Variable(id), substitutions, iteration).id
  }

  /**
   * Unroll a [variable] for a given [iteration]. Copies the variable, renames
   * it, and puts the old name and the new variable into the given map of
   * [substitutions].
   */
  private fun unrollVariable(variable: Variable,
      substitutions: MutableMap<String, Variable>, iteration: Int): Variable {
    val newVarId = "${variable.id}$$iteration"
    val newVar = variable.copy(id = newVarId)
    substitutions[variable.id] = newVar
    return newVar
  }

  /**
   * Create process chains for all actions that are ready to be executed (i.e.
   * whose inputs are all available) and remove these actions from [actions].
   */
  private fun createProcessChains(): List<ProcessChain> {
    // build an index from input variables to actions
    val inputsToActions = IdentityHashMap<Variable, MutableList<ExecuteAction>>()
    val dependsOnToActions = mutableMapOf<String, MutableList<ExecuteAction>>()
    for (action in actions) {
      if (action is ExecuteAction) {
        for (param in action.inputs) {
          inputsToActions.getOrPut(param.variable, ::mutableListOf).add(action)
        }
        for (target in action.dependsOn) {
          dependsOnToActions.getOrPut(target, ::mutableListOf).add(action)
        }
      }
    }

    // create process chains
    val processChains = mutableListOf<ProcessChain>()
    val actionsToRemove = Collections.newSetFromMap(IdentityHashMap<ExecuteAction, Boolean>())
    val actionsVisited = Collections.newSetFromMap(IdentityHashMap<ExecuteAction, Boolean>())
    for (action in actions) {
      if (action !is ExecuteAction) {
        continue
      }

      val executables = mutableListOf<Executable>()
      val executableIds = mutableSetOf<String>()
      val capabilities = mutableSetOf<String>()
      val argumentValues = mutableMapOf<String, String>()

      var nextAction: ExecuteAction = action
      while (!actionsVisited.contains(nextAction)) {
        // check if action is executable:
        // (a) check if all actions that this action depends on have already
        // been executed or are about to be executed in the process chain we
        // are currently creating
        // (b) check if all inputs are set (either because the variable has a
        // value or because the value has been calculated earlier)
        val allDependingExecuted = nextAction.dependsOn.all { target ->
          executedActionIds.contains(target) || executableIds.contains(target) }
        val allInputsAvailable = nextAction.inputs.all { it.variable.value != null ||
            it.variable.id in variableValues || it.variable.id in argumentValues }
        val isExecutable = allDependingExecuted && allInputsAvailable
        if (isExecutable) {
          val newExecutable = actionToExecutable(nextAction, capabilities, argumentValues)
          executables.add(newExecutable)
          executableIds.add(newExecutable.id)

          // do not visit this action again
          actionsToRemove.add(nextAction)
          actionsVisited.add(nextAction)

          if (newExecutable.arguments.any { it.type == OUTPUT &&
                  pluginRegistry.findOutputAdapter(it.dataType) != null }) {
            // stop here if the new executable's output would be modified
            // by an output adapter
            break
          }

          // try to find next action
          val moreActionsByOutput = nextAction.outputs.map { it.variable }.flatMap {
            inputsToActions[it] ?: mutableListOf() }
          val moreActionsByDependsOn = dependsOnToActions[nextAction.id] ?: emptyList()
          val moreActions = (moreActionsByOutput + moreActionsByDependsOn).distinct()
          if (moreActions.size != 1) {
            // leverage parallelization and stop if there are more than one
            // next actions (i.e. if the process chain would fork)
            break
          }
          nextAction = moreActions[0]
        } else {
          // the action is not executable at the moment - do not visit it
          // again unless it was the first action (it could depend on the
          // results of another action that we haven't visited yet)
          if (nextAction !== action) {
            actionsVisited.add(nextAction)
          }
          break
        }
      }

      if (executables.isNotEmpty()) {
        processChains.add(ProcessChain(idGenerator.next(), executables,
            capabilities, defaultPriority))
      }
    }

    // do not touch these actions again
    actions.removeAll(actionsToRemove)

    return processChains
  }

  /**
   * Generate a value for an output argument based on a [serviceParam]
   * definition and an [outputParam]
   */
  private fun makeOutput(serviceParam: ServiceParameter, outputParam: OutputParameter): String {
    val prefix = outputParam.prefix
    val base = if (outputParam.store) outPath else tmpPath
    val p = if (prefix is String) {
      if (prefix.startsWith("/")) {
        prefix
      } else {
        "$base/$prefix"
      }
    } else {
      "$base/"
    }
    return FilenameUtils.normalize(p + idGenerator.next() + (serviceParam.fileSuffix ?: ""))
  }

  /**
   * Converts an [ExecuteAction] to an [Executable].
   * @param action the [ExecuteAction] to convert
   * @param capabilities a set that will be filled with the capabilities that
   * the executable needs to be able to run
   * @param argumentValues a map that will be filled with the values of all
   * generated arguments
   * @return the created [Executable]
   */
  private fun actionToExecutable(action: ExecuteAction, capabilities: MutableSet<String>,
      argumentValues: MutableMap<String, String>): Executable {
    // find matching service metadata
    val service = services.find { it.id == action.service } ?: throw IllegalStateException(
        "There is no service with ID `${action.service}'")

    // add capabilities
    capabilities.addAll(service.requiredCapabilities)

    val arguments = service.parameters.flatMap flatMap@ { serviceParam ->
      // look for action parameters matching the service parameter's ID
      val params = (when (serviceParam.type) {
        INPUT -> action.inputs
        OUTPUT -> action.outputs
      }).filter { it.id == serviceParam.id }

      // convert parameters to arguments
      val args = params.flatMap { param ->
        val vs = if (serviceParam.type == OUTPUT) {
          listOf(makeOutput(serviceParam, param as OutputParameter))
        } else {
          val iv = param.variable.value ?:
            mergeToDir(variableValues[param.variable.id], serviceParam) ?:
            argumentValues[param.variable.id] ?:
            serviceParam.default ?:
            throw IllegalStateException("Parameter `${param.id}' does not have a value")
          if (iv is Collection<*>) {
            toStringCollection(iv)
          } else {
            listOf(iv.toString())
          }
        }

        vs.map { v ->
          argumentValues[param.variable.id] = v
          Argument(serviceParam.id, serviceParam.label,
              ArgumentVariable(param.variable.id, v),
              serviceParam.type, serviceParam.dataType)
        }
      }

      // if there are no arguments but the serviceParam is required and has a
      // default value, add a new argument (does not apply to inputs or outputs!)
      if (args.isEmpty() && serviceParam.cardinality.min == 1 &&
          serviceParam.cardinality.max == 1 && serviceParam.type == INPUT &&
          serviceParam.default != null) {
        return@flatMap listOf(Argument(serviceParam.id, serviceParam.label,
            ArgumentVariable(idGenerator.next(), serviceParam.default.toString()),
            serviceParam.type, serviceParam.dataType))
      }

      // validate cardinality
      if (args.size < serviceParam.cardinality.min ||
          args.size > serviceParam.cardinality.max) {
        throw IllegalStateException("Illegal number of parameters. Parameter " +
            "`${serviceParam.id}' appears ${args.size} times but its " +
            "cardinality is defined as ${serviceParam.cardinality}.")
      }

      return@flatMap args
    }

    return Executable(action.id, service.path, service.id, arguments,
        service.runtime, runtimeArgsToArguments(service.runtimeArgs),
        action.retries ?: service.retries, action.maxInactivity ?: service.maxInactivity,
        action.maxRuntime ?: service.maxRuntime, action.deadline ?: service.deadline)
  }

  /**
   * Get the next iteration for the given [enumId]
   */
  private fun nextIteration(enumId: String) =
      iterations.merge(enumId, 0) { i, _ -> i + 1 } ?: 0

  /**
   * Convert a [collection] of values to a flat list of strings
   */
  private fun toStringCollection(collection: Collection<*>): Collection<String> =
      collection.flatMap {
        if (it is Collection<*>) {
          toStringCollection(it)
        } else {
          listOf(it.toString())
        }
      }

  /**
   * Append the items from [outputs] to [dest]. If [dest] is an object, it will
   * be converted to a list. If it is `null`, it will be converted to an empty
   * list. Each item from [outputs] will then be appended to [dest] but empty
   * lists will be removed and nested lists will be flattened. If [outputs] is
   * empty, [dest] will be returned as is.
   *
   * Examples:
   *
   *     null + [] -> []
   *     null + ["b"] -> ["b"]
   *     "a" + [] -> "a"
   *     "a" + ["b"] -> ["a", "b"]
   *     [] + [] -> []
   *     [] + ["b"] -> ["b"]
   *     ["a"] + ["b"] -> ["a", "b"]
   *     "a" + ["b", [], "c"] -> ["a", "b", "c"]
   *     ["a", "b"] + ["c", [], ["d", "e"]] -> ["a", "b", "c", "d", "e"]
   *     ["a", "b"] + ["c", [], ["d", ["e"]]] -> ["a", "b", "c", "d", ["e"]]
   */
  private fun yieldTo(dest: Any?, outputs: Collection<Any>): Any {
    if (outputs.isEmpty()) {
      return dest ?: emptyList<String>()
    }

    val result: MutableList<Any?> = when (dest) {
      null -> mutableListOf()
      is Collection<*> -> dest.toMutableList()
      else -> mutableListOf(dest)
    }

    for (o in outputs) {
      when (o) {
        is Collection<*> -> result.addAll(o)
        else -> result.add(o)
      }
    }

    return result
  }

  /**
   * Rename a temporary variable with the given [outputId] back to its
   * original name without [TMP_OUTPUT_SUFFIX]
   */
  private fun renameTemporaryOutput(outputId: String) {
    val v = variableValues[outputId]
    if (v != null) {
      variableValues[outputId.removeSuffix(TMP_OUTPUT_SUFFIX)] = v
      variableValues.remove(outputId)
    }
  }

  /**
   * If the given [serviceParam] is an input directory, get the common directory
   * from the given list of [values] (i.e. files). Otherwise, just return
   * [values]. Note that we only apply this method to [variableValues]. We know
   * that we can do this because [variableValues] only contains results from
   * previous process chains and collections in [variableValues] will therefore
   * always have been generated by [helper.FileSystemUtils.readRecursive].
   */
  private fun mergeToDir(values: Any?, serviceParam: ServiceParameter): Any? {
    if (values !is Collection<*>) {
      return values
    }

    // only merge if parameter is an input directory
    if (serviceParam.type != INPUT ||
        serviceParam.dataType != Argument.DATA_TYPE_DIRECTORY) {
      return values
    }

    if (values.isEmpty()) {
      throw IllegalStateException("Cannot merge empty list of files to directory")
    }

    // find common directory
    val vss = toStringCollection(values)
    log.debug("Merging ${vss.size} files to directory")
    val commonPrefix = StringUtils.getCommonPrefix(*vss.toTypedArray())
    val lastSeparator = FilenameUtils.indexOfLastSeparator(commonPrefix)
    return listOf(commonPrefix.substring(0, lastSeparator))
  }

  /**
   * Convert a list of [RuntimeArgument]s to a list of [Argument]s
   */
  private fun runtimeArgsToArguments(runtimeArgs: List<RuntimeArgument>) =
      runtimeArgs.map {
        Argument(id = idGenerator.next(),
            label = it.label,
            variable = ArgumentVariable(it.id, it.value ?: ""),
            type = INPUT,
            dataType = it.dataType)
      }

  /**
   * A helper class that is used to persist the generator's state in [persistState]
   */
  private data class State(
      val vars: List<Variable>,
      val actions: List<Action>,
      val variableValues: Map<String, Any>,
      val executedActionIds: Set<String> = emptySet(),
      val forEachOutputsToBeCollected: Map<String, List<Variable>>,
      val forEachOutputsReadyToBeRenamed: Set<String> = emptySet(),
      val forEachSubActionsToWaitFor: Map<String, Set<String>> = emptyMap(),
      val iterations: Map<String, Int>
  )

  /**
   * Persist the generator's internal state to a JSON object
   */
  fun persistState(): JsonObject {
    val s = State(vars, actions.toList(), variableValues, executedActionIds,
        forEachOutputsToBeCollected, forEachOutputsReadyToBeRenamed,
        forEachSubActionsToWaitFor, iterations)
    return JsonUtils.toJson(s)
  }

  /**
   * Load the generator's internal state from a JSON object (current state
   * will be overwritten)
   */
  fun loadState(state: JsonObject) {
    vars.clear()
    actions.clear()
    variableValues.clear()
    executedActionIds.clear()
    forEachOutputsToBeCollected.clear()
    forEachOutputsReadyToBeRenamed.clear()
    forEachSubActionsToWaitFor.clear()
    iterations.clear()

    val s: State = JsonUtils.fromJson(state)
    vars.addAll(s.vars)
    actions.addAll(s.actions)
    variableValues.putAll(s.variableValues)
    executedActionIds.addAll(s.executedActionIds)
    forEachOutputsToBeCollected.putAll(s.forEachOutputsToBeCollected)
    forEachOutputsReadyToBeRenamed.addAll(s.forEachOutputsReadyToBeRenamed)
    forEachSubActionsToWaitFor.putAll(s.forEachSubActionsToWaitFor)
    iterations.putAll(s.iterations)
  }
}
