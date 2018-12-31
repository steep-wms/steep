import helper.IDGenerator
import helper.UniqueID
import model.metadata.Service
import model.processchain.Argument
import model.processchain.Argument.Type.ARGUMENT
import model.processchain.Argument.Type.INPUT
import model.processchain.Argument.Type.OUTPUT
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.ExecuteAction
import model.workflow.Variable
import model.workflow.Workflow
import org.apache.commons.io.FilenameUtils
import java.util.ArrayDeque
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
  private val vars: Map<String, Variable> = workflow.vars.map { it.id to it }.toMap()
  private val actions = workflow.actions.toMutableList()

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
    val actionsToRemove = mutableSetOf<ExecuteAction>()
    for (action in actions) {
      if (actionsToRemove.contains(action) || action !is ExecuteAction) {
        continue
      }

      // convert action if all inputs are available
      val allInputsAvailable = action.inputs.all { it.variable.value != null }
      if (!allInputsAvailable) {
        continue
      }

      val executables = mutableListOf<Executable>()
      executables.add(actionToExecutable(action))
      actionsToRemove.add(action)

      // try to convert as many subsequent actions as possible
      val outputVariablesToCheck = ArrayDeque(action.outputs.map { it.variable })
      while (!outputVariablesToCheck.isEmpty()) {
        val outputVariable = outputVariablesToCheck.poll()
        inputsToActions[outputVariable]?.forEach { nextAction ->
          val isExecutable = nextAction.inputs.all { it.variable.value != null }
          if (isExecutable) {
            executables.add(actionToExecutable(nextAction))
            actionsToRemove.add(nextAction)
            val newOutputVariables = nextAction.outputs.map { it.variable }
            outputVariablesToCheck.addAll(newOutputVariables)
          }
        }
      }

      processChains.add(ProcessChain(idGenerator.next(), executables))
    }

    // do not touch these actions again
    actions.removeAll(actionsToRemove)

    return processChains
  }

  /**
   * Converts an [ExecuteAction] to an [Executable].
   *
   * **Note:** This method has a side-effect: It sets the action's outputs to
   * valid values and also sets parameters to their default values (if they
   * have default values).
   *
   * @param action the [ExecuteAction] to convert
   * @return the created [Executable]
   */
  private fun actionToExecutable(action: ExecuteAction): Executable {
    // find matching service metadata
    val service = services.find { it.id == action.service } ?: throw IllegalStateException(
        "There is no service with ID `${action.service}'")

    val arguments = service.parameters.flatMap { serviceParam ->
      // look for action parameters matching the service parameter's ID
      val params = (when (serviceParam.type) {
        INPUT -> action.inputs
        OUTPUT -> action.outputs
        ARGUMENT -> action.parameters
      }).filter { it.id == serviceParam.id }

      // validate cardinality
      if (params.size < serviceParam.cardinality.min ||
          params.size > serviceParam.cardinality.max) {
        throw IllegalStateException("Illegal number of parameters. Parameter " +
            "`${serviceParam.id}' appears ${params.size} times but its " +
            "cardinality is defined as ${serviceParam.cardinality}.")
      }

      // convert parameters to arguments
      params.map { param ->
        // side-effect: the Action's parameters will be set to valid values
        // if possible
        val v = if (serviceParam.type == OUTPUT) {
          param.variable.value = FilenameUtils.normalize("$tmpPath/" +
              idGenerator.next() + (serviceParam.fileSuffix ?: ""))
          param.variable.value
        } else {
          if (param.variable.value == null) {
            param.variable.value = serviceParam.default
          }
          param.variable.value ?: throw IllegalStateException(
              "Parameter `${param.id}' does not have a value")
        }

        Argument(serviceParam.id, serviceParam.label, v.toString(),
            serviceParam.type, serviceParam.dataType)
      }
    }

    return Executable(service.name, service.path, arguments)
  }
}
