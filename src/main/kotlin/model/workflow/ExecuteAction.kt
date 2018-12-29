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
    val inputs: List<Parameter> = emptyList(),
    val outputs: List<Parameter> = emptyList(),
    val parameters: List<Parameter> = emptyList()
) : Action
