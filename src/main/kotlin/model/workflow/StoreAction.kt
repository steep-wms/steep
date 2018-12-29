package model.workflow

/**
 * An action that stores a given result
 * @inputs the results to store
 * @author Michel Kraemer
 */
data class StoreAction(
    val inputs: List<Variable> = emptyList()
) : Action
