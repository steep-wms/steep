package model.workflow

/**
 * A workflow action that iterates over the value(s) of a given `input` variable
 * and passes the values on to its child `actions` through a given `enumerator`.
 * @param input the input variable to iterate over
 * @param enumerator the variable that will hold the current input value
 * @param actions the actions to execute for each value in the input variable
 * @author Michel Kraemer
 */
data class ForEachAction(
    val input: Variable,
    val enumerator: Variable,
    val actions: List<Action> = emptyList()
) : Action
