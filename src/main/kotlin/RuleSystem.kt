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

/**
 * The rule system applies production rules to convert a workflow to a list
 * of process chains.
 * @param workflow the workflow to convert
 * @param tmpPath a directory where temporary workflow results should be stored
 * @param services service metadata
 */
class RuleSystem(workflow: Workflow, private val tmpPath: String,
    private val services: List<Service>) {
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

    // create executables for all ExecuteActions
    val executables = mutableListOf<Executable>()
    for (action in actions) {
      if (action is ExecuteAction) {
        val service = services.find { it.id == action.service } ?: throw IllegalStateException(
            "There is no service with ID `${action.service}'")
        executables.add(actionToExecutable(action, service))
      }
    }

    return listOf(ProcessChain(executables = executables))
  }

  /**
   * Converts an [ExecuteAction] to an [Executable]
   * @param action the [ExecuteAction] to convert
   * @param service the metadata that describes the service to be called
   * @return the created [Executable]
   */
  private fun actionToExecutable(action: ExecuteAction, service: Service): Executable {
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
        val v = if (serviceParam.type == OUTPUT) {
          FilenameUtils.normalize("$tmpPath/" + UniqueID.next() + (serviceParam.fileSuffix ?: ""))
        } else {
          param.variable.value ?: serviceParam.default ?: throw IllegalStateException(
              "Parameter `${param.id}' does not have a value")
        }

        Argument(serviceParam.id, serviceParam.label, v.toString(),
            serviceParam.type, serviceParam.dataType)
      }
    }
    return Executable(service.name, service.path, arguments)
  }
}
