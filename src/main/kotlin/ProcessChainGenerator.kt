import db.PluginRegistryFactory
import helper.IDGenerator
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.json.JsonObject
import model.metadata.RuntimeArgument
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument
import model.processchain.Argument.Type.ARGUMENT
import model.processchain.Argument.Type.INPUT
import model.processchain.Argument.Type.OUTPUT
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.Action
import model.workflow.ExecuteAction
import model.workflow.ForEachAction
import model.workflow.OutputParameter
import model.workflow.StoreAction
import model.workflow.Variable
import model.workflow.Workflow
import org.apache.commons.io.FilenameUtils
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Generates process chains from a workflow
 * @param workflow the workflow to convert to process chains
 * @param tmpPath a directory where temporary workflow results should be stored
 * @param services service metadata
 */
class ProcessChainGenerator(workflow: Workflow, private val tmpPath: String,
    private val services: List<Service>, private val idGenerator: IDGenerator = UniqueID) {
  companion object {
    private val log = LoggerFactory.getLogger(ProcessChainGenerator::class.java)
  }

  private val vars = workflow.vars.toMutableList()
  private val actions = workflow.actions.toMutableSet()
  private val variableValues = mutableMapOf<String, Any>()
  private val forEachOutputsToBeCollected = mutableMapOf<String, List<Variable>>()
  private val pluginRegistry = PluginRegistryFactory.create()

  /**
   * Returns `true` if the workflow has been fully converted to process chains
   */
  fun isFinished() = actions.isEmpty()

  /**
   * Create the next set of process chains. Call this method until it returns
   * an empty list (i.e. until it does not produce more process chains).
   * Execute the process chains after each call to this method and pass their
   * [results] to the next call.
   */
  fun generate(results: Map<String, List<Any>>? = null): List<ProcessChain> {
    // replace variable values with results
    results?.forEach { key, value ->
      variableValues[key] = if (value.size == 1) value[0] else value
    }

    // collect for-each outputs
    do {
      var didCollectOutputs = false
      val i = forEachOutputsToBeCollected.iterator()
      for ((outputId, outputsToCollect) in i) {
        // try to get values of all outputs
        val collectedOutputs = mutableListOf<Any>()
        for (o in outputsToCollect) {
          val v = o.value ?: variableValues[o.id] ?: break
          collectedOutputs.add(v)
        }

        // if all values are available, make the for-each action's output
        // available too and remove the item from `forEachOutputsToBeCollected`
        if (collectedOutputs.size == outputsToCollect.size) {
          variableValues[outputId] = collectedOutputs
          i.remove()

          // repeat until no outputs were collected anymore
          didCollectOutputs = true
        }
      }
    } while (didCollectOutputs)

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
      val action = foreachActions.poll()
      val enumId = action.enumerator.id

      // get inputs of for-each actions if they are available, otherwise continue
      val input = action.input.value ?: variableValues[action.input.id] ?: continue
      val inputCollection = if (input is Collection<*>) input else listOf(input)

      // unroll for-each action
      val yieldedOutputs = mutableListOf<Variable>()
      for ((iteration, enumValue) in inputCollection.withIndex()) {
        // for each iteration, generate new identifier for enumerator
        val iid = "$enumId$$iteration"
        val substitutions = mutableMapOf(enumId to Variable(iid, enumValue))

        // copy sub-actions and append them to `actions`
        for (subAction in action.actions) {
          val unrolledSubAction = unrollAction(subAction, substitutions, iteration)
          actions.add(unrolledSubAction)

          // recursively unroll for-each actions
          if (unrolledSubAction is ForEachAction) {
            foreachActions.add(unrolledSubAction)
          }
        }

        // collect yielded output
        if (action.yieldToOutput != null) {
          val subst = substitutions[action.yieldToOutput.id] ?: throw IllegalStateException(
              "Cannot yield non-existing variable `${action.yieldToOutput.id}'")
          yieldedOutputs.add(subst)
        }
      }

      // keep track of outputs that need to be collected
      if (action.output != null) {
        forEachOutputsToBeCollected[action.output.id] = yieldedOutputs
      }

      // remove unrolled for-each action
      actions.remove(action)
    }
  }

  /**
   * Recursively unroll a given [action] by appending the current [iteration] to
   * its outputs (or enumerator if it's a for-each action) and replacing
   * variables with the given [substitutions].
   */
  private fun unrollAction(action: Action, substitutions: MutableMap<String, Variable>,
      iteration: Int): Action {
    return when (action) {
      is ExecuteAction -> {
        action.copy(inputs = action.inputs.map {
          it.copy(variable = substitutions[it.variable.id] ?: it.variable)
        }, outputs = action.outputs.map {
          it.copy(variable = unrollVariable(it.variable, substitutions, iteration))
        })
      }

      is ForEachAction -> {
        val newEnum = unrollVariable(action.enumerator, substitutions, iteration)
        val newOutput = action.output?.let { unrollVariable(it, substitutions, iteration) }
        val newActions = action.actions.map { unrollAction(it, substitutions, iteration) }
        action.copy(input = substitutions[action.input.id] ?: action.input,
            enumerator = newEnum, output = newOutput, actions = newActions,
            yieldToOutput = action.yieldToOutput?.let { substitutions[it.id] ?: it })
      }

      is StoreAction -> {
        action.copy(inputs = action.inputs.map { substitutions[it.id] ?: it })
      }

      else -> throw RuntimeException("Unknown action type `${action.javaClass}'")
    }
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
    for (action in actions) {
      if (action is ExecuteAction) {
        for (param in action.inputs) {
          inputsToActions.getOrPut(param.variable, ::mutableListOf).add(action)
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
      val capabilities = mutableSetOf<String>()
      val argumentValues = mutableMapOf<String, String>()

      var nextAction: ExecuteAction = action
      while (!actionsVisited.contains(nextAction)) {
        // check if all inputs are set (either because the variable has a value
        // or because the value has been calculated earlier)
        val isExecutable = nextAction.inputs.all { it.variable.value != null ||
            it.variable.id in variableValues || it.variable.id in argumentValues }
        if (isExecutable) {
          val newExecutable = actionToExecutable(nextAction, capabilities, argumentValues)
          executables.add(newExecutable)

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
          val moreActions = nextAction.outputs.map { it.variable }.flatMap {
            inputsToActions[it] ?: mutableListOf() }.distinct()
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
        processChains.add(ProcessChain(idGenerator.next(), executables, capabilities))
      }
    }

    // do not touch these actions again
    actions.removeAll(actionsToRemove)

    return processChains
  }

  /**
   * Generate a value for an output argument based on a [serviceParam]
   * definition and an optional [prefix]
   */
  private fun makeOutput(serviceParam: ServiceParameter, prefix: Any?): String {
    val p = if (prefix is String) {
      if (prefix.startsWith("/")) {
        prefix
      } else {
        "$tmpPath/$prefix"
      }
    } else {
      "$tmpPath/"
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
        ARGUMENT -> action.parameters
      }).filter { it.id == serviceParam.id }

      // convert parameters to arguments
      val args = params.flatMap { param ->
        val vs = if (serviceParam.type == OUTPUT) {
          listOf(makeOutput(serviceParam, (param as OutputParameter).prefix))
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
          serviceParam.cardinality.max == 1 && serviceParam.type == ARGUMENT &&
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

    return when (service.runtime) {
      // Handle Docker runtime: Call Docker instead of the service executable
      // and use the service path as Docker image.
      Service.Runtime.DOCKER -> {
        val path = "docker"
        val dockerArgs = listOf(
            Argument(id = idGenerator.next(),
                variable = ArgumentVariable("dockerRun", "run"),
                type = ARGUMENT)
        ) + runtimeArgsToArguments(service.runtimeArgs) + listOf(
            Argument(id = idGenerator.next(),
                label = "-v", variable = ArgumentVariable("dockerMount", "$tmpPath:$tmpPath"),
                type = ARGUMENT),
            Argument(id = idGenerator.next(),
                variable = ArgumentVariable("dockerImage", service.path),
                type = ARGUMENT)
        )
        Executable(service.name, path, dockerArgs + arguments)
      }

      else -> Executable(service.name, service.path, arguments)
    }
  }

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
            type = ARGUMENT,
            dataType = it.dataType)
      }

  /**
   * A helper class that is used to persist the generator's state in [persistState]
   */
  private data class State(
      val vars: List<Variable>,
      val actions: List<Action>,
      val variableValues: Map<String, Any>,
      val forEachOutputsToBeCollected: Map<String, List<Variable>>
  )

  /**
   * Persist the generator's internal state to a JSON object
   */
  fun persistState(): JsonObject {
    val s = State(vars, actions.toList(), variableValues, forEachOutputsToBeCollected)
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
    forEachOutputsToBeCollected.clear()

    val s: State = JsonUtils.fromJson(state)
    vars.addAll(s.vars)
    actions.addAll(s.actions)
    variableValues.putAll(s.variableValues)
    forEachOutputsToBeCollected.putAll(s.forEachOutputsToBeCollected)
  }
}
