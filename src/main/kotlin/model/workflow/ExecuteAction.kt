package model.workflow

/**
 * A workflow action that executes a service
 * @param service the name of the service to execute
 * @param inputs the service inputs
 * @param outputs the service outputs
 * @param parameters the service parameters
 * @author Michel Kraemer
 */
data class ExecuteAction(
    val service: String,
    val inputs: List<GenericParameter> = emptyList(),
    val outputs: List<OutputParameter> = emptyList(),
    val parameters: List<GenericParameter> = emptyList()
) : Action
