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
import model.workflow.ExecuteAction
import model.workflow.Variable
import model.workflow.Workflow
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
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

  private val vars: Map<String, Variable> = workflow.vars.map { it.id to it }.toMap()
  private val actions = workflow.actions.toMutableList()

  /**
   * Returns `true` if the workflow has been fully converted to process chains
   * @return `true` if the rule system has finished, `false` otherwise
   */
  fun isFinished() = actions.isEmpty()

  /**
   * Evaluate the production rules and create the next set of process chains.
   * Call this method until it returns an empty list (i.e. until it does not
   * produce more process chains). Execute the process chains after each call
   * to this method and pass their output to the next call.
   * @param outputs the output of previously generated process chains
   * @return a list of process chains (may be empty if there are no process
   * chains anymore)
   */
  fun fire(outputs: Map<String, List<String>>? = null): List<ProcessChain> {
    // replace variable values with outputs
    outputs?.forEach { key, value ->
      val v = vars[key]
      v?.value = if (value.size == 1) value[0] else value
    }

    // build an index from input variables to actions
    val inputsToActions = IdentityHashMap<Variable, MutableList<ExecuteAction>>()
    for (action in actions) {
      if (action is ExecuteAction) {
        for (param in action.inputs) {
          inputsToActions.getOrPut(param.variable, ::mutableListOf).add(action)
        }
      }
    }

    // create process chains for all actions that are ready to be executed (i.e.
    // whose inputs are all available)
    val processChains = mutableListOf<ProcessChain>()
    val actionsToRemove = Collections.newSetFromMap(IdentityHashMap<ExecuteAction, Boolean>())
    val actionsVisited = Collections.newSetFromMap(IdentityHashMap<ExecuteAction, Boolean>())
    for (action in actions) {
      if (action !is ExecuteAction) {
        continue
      }

      val executables = mutableListOf<Executable>()
      val argumentValues = mutableMapOf<String, String>()

      var nextAction: ExecuteAction = action
      while (!actionsVisited.contains(nextAction)) {
        // check if all inputs are set (either because the variable has a value
        // or because the value has been calculated earlier)
        val isExecutable = nextAction.inputs.all { it.variable.value != null ||
            argumentValues.contains(it.variable.id) }
        if (isExecutable) {
          executables.add(actionToExecutable(nextAction, argumentValues))

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
        processChains.add(ProcessChain(idGenerator.next(), executables))
      }
    }

    // do not touch these actions again
    actions.removeAll(actionsToRemove)

    if (processChains.isNotEmpty()) {
      log.debug("Generated process chains:\n" + JsonUtils.mapper.copy()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(processChains))
    }

    return processChains
  }

  /**
   * Converts an [ExecuteAction] to an [Executable].
   * @param action the [ExecuteAction] to convert
   * @param argumentValues a map that will be filled with the values of all
   * generated arguments
   * @return the created [Executable]
   */
  private fun actionToExecutable(action: ExecuteAction,
      argumentValues: MutableMap<String, String>): Executable {
    // find matching service metadata
    val service = services.find { it.id == action.service } ?: throw IllegalStateException(
        "There is no service with ID `${action.service}'")

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
          (param.variable.value ?: argumentValues[param.variable.id] ?:
              serviceParam.default ?: throw IllegalStateException(
                  "Parameter `${param.id}' does not have a value")).toString()
        }

        argumentValues[param.variable.id] = v
        Argument(serviceParam.id, serviceParam.label,
            ArgumentVariable(param.variable.id, v),
            serviceParam.type, serviceParam.dataType)
      }
    }

    return Executable(service.name, service.path, arguments)
  }
}
