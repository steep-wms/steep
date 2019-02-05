import com.fasterxml.jackson.databind.SerializationFeature
import helper.IDGenerator
import helper.JsonUtils
import helper.UniqueID
import model.metadata.Service
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
import model.workflow.StoreAction
import model.workflow.Variable
import model.workflow.Workflow
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The rule system applies production rules to convert a workflow to a list
 * of process chains.
 * @param workflow the workflow to convert
 * @param tmpPath a directory where temporary workflow results should be stored
 * @param services service metadata
 */
class RuleSystem(workflow: Workflow, private val tmpPath: String,
    private val services: List<Service>, private val idGenerator: IDGenerator = UniqueID) {
  companion object {
    private val log = LoggerFactory.getLogger(RuleSystem::class.java)
  }

  private val actions = workflow.actions.toMutableSet()
  private val variableValues = mutableMapOf<String, Any>()

  /**
   * Returns `true` if the workflow has been fully converted to process chains
   * @return `true` if the rule system has finished, `false` otherwise
   */
  fun isFinished() = actions.isEmpty()

  /**
   * Evaluate the production rules and create the next set of process chains.
   * Call this method until it returns an empty list (i.e. until it does not
   * produce more process chains). Execute the process chains after each call
   * to this method and pass their results to the next call.
   * @param results the results of previously generated process chains
   * @return a list of process chains (may be empty if there are no process
   * chains anymore)
   */
  fun fire(results: Map<String, List<String>>? = null): List<ProcessChain> {
    // replace variable values with results
    results?.forEach { key, value ->
      variableValues[key] = if (value.size == 1) value[0] else value
    }

    unrollForEachActions()
    val processChains = createProcessChains()

    if (processChains.isNotEmpty()) {
      log.debug("Generated process chains:\n" + JsonUtils.mapper.copy()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(processChains))
    }

    return processChains
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
          val newVarId = "${it.variable.id}$$iteration"
          val newVar = it.variable.copy(id = newVarId)
          substitutions[it.variable.id] = newVar
          it.copy(variable = newVar)
        })
      }

      is ForEachAction -> {
        val newEnumId = "${action.enumerator.id}$$iteration"
        val newEnum = action.enumerator.copy(id = newEnumId)
        substitutions[action.enumerator.id] = newEnum
        action.copy(input = substitutions[action.input.id] ?: action.input,
            enumerator = newEnum,
            actions = action.actions.map { unrollAction(it, substitutions, iteration) })
      }

      is StoreAction -> {
        action.copy(inputs = action.inputs.map { substitutions[it.id] ?: it })
      }

      else -> throw RuntimeException("Unknown action type `${action.javaClass}'")
    }
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
          executables.add(actionToExecutable(nextAction, capabilities, argumentValues))

          // do not visit this action again
          actionsToRemove.add(nextAction)
          actionsVisited.add(nextAction)

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

      // if there are no params but the serviceParam is required and has a
      // default value, add a new argument (does not apply to inputs or outputs!)
      if (params.isEmpty() && serviceParam.cardinality.min == 1 &&
          serviceParam.cardinality.max == 1 && serviceParam.type == ARGUMENT &&
          serviceParam.default != null) {
        return@flatMap listOf(Argument(serviceParam.id, serviceParam.label,
            ArgumentVariable(idGenerator.next(), serviceParam.default.toString()),
            serviceParam.type, serviceParam.dataType))
      }

      // validate cardinality
      if (params.size < serviceParam.cardinality.min ||
          params.size > serviceParam.cardinality.max) {
        throw IllegalStateException("Illegal number of parameters. Parameter " +
            "`${serviceParam.id}' appears ${params.size} times but its " +
            "cardinality is defined as ${serviceParam.cardinality}.")
      }

      // convert parameters to arguments
      return@flatMap params.map { param ->
        val v = if (serviceParam.type == OUTPUT) {
          FilenameUtils.normalize("$tmpPath/" +
              idGenerator.next() + (serviceParam.fileSuffix ?: ""))!!
        } else {
          (param.variable.value ?:
            variableValues[param.variable.id] ?:
            argumentValues[param.variable.id] ?:
            serviceParam.default ?:
            throw IllegalStateException("Parameter `${param.id}' does not have a value")
          ).toString()
        }

        argumentValues[param.variable.id] = v
        Argument(serviceParam.id, serviceParam.label,
            ArgumentVariable(param.variable.id, v),
            serviceParam.type, serviceParam.dataType)
      }
    }

    return when (service.runtime) {
      // Handle Docker runtime: Call Docker instead of the service executable
      // and use the service path as Docker image.
      Service.Runtime.DOCKER -> {
        val path = "docker"
        val dockerArgs = listOf(
            Argument(id = idGenerator.next(),
                variable = ArgumentVariable("dockerRun", "run"),
                type = Argument.Type.ARGUMENT),
            // TODO HACK: hardcoded mount path
            Argument(id = idGenerator.next(),
                label = "-v", variable = ArgumentVariable("dockerMountHack", "/data:/data"),
                type = Argument.Type.ARGUMENT),
            Argument(id = idGenerator.next(),
                label = "-v", variable = ArgumentVariable("dockerMount", "$tmpPath:$tmpPath"),
                type = Argument.Type.ARGUMENT),
            Argument(id = idGenerator.next(),
                variable = ArgumentVariable("dockerImage", service.path),
                type = Argument.Type.ARGUMENT)
        )
        Executable(service.name, path, dockerArgs.plus(arguments))
      }

      else -> Executable(service.name, service.path, arguments)
    }
  }
}
