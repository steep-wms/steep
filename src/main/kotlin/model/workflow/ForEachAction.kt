package model.workflow

import helper.UniqueID

/**
 * A workflow action that iterates over the value(s) of a given [input] variable
 * and passes the values on to its child [actions] through a given [enumerator].
 * @param input the input variable to iterate over
 * @param enumerator the variable that will hold the current input value
 * @param output the variable that will collect the output of the sub-actions
 * @param actions the actions to execute for each value in the input variable
 * @param yieldToOutput a reference to an output variable of a sub-action
 * specifying what should be collected in the for-each action's [output] variable
 * @param yieldToInput a reference to an output variable of a sub-action
 * whose value should be pushed back into the for-each action's [input] variable
 * @author Michel Kraemer
 */
data class ForEachAction(
    val input: Variable,
    val enumerator: Variable,
    val output: Variable? = null,
    val actions: List<Action> = emptyList(),
    val yieldToOutput: Variable? = null,
    val yieldToInput: Variable? = null,
    override val id: String = UniqueID.next(),
    override val dependsOn: List<String> = emptyList()
) : Action
