package model.workflow

/**
 * An action that stores a given result
 * @deprecated Use [OutputParameter.store] instead
 * @inputs the results to store
 * @author Michel Kraemer
 */
@Deprecated("Store actions are scheduled to be removed in workflow API " +
    "version 4.0.0. Use [OutputParameter.store] instead.")
data class StoreAction(
    val inputs: List<Variable> = emptyList()
) : Action
