package model.workflow

/**
 * A workflow action that executes a service
 * @param service the name of the service to execute
 * @param inputs the service inputs
 * @param outputs the service outputs
 * @author Michel Kraemer
 */
data class ExecuteAction(
    val service: String,
    val inputs: List<GenericParameter> = emptyList(),
    val outputs: List<OutputParameter> = emptyList(),

    @Deprecated(message = "Action parameters are deprecated. Use inputs " +
        "instead. This property is only kept for backward compatibility. It " +
        "will be removed in Steep 6.0.0 with a corresponding database " +
        "migration.", replaceWith = ReplaceWith("inputs"))
    val parameters: List<GenericParameter> = emptyList()
) : Action
