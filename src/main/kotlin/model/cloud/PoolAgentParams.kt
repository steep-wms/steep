package model.cloud

/**
 * Parameters of remote agents the [cloud.CloudManager] should keep in its pool
 * @param capabilities the capabilities the agent instances should provide
 * @param min the minimum number of remote agents to create with the given
 * [capabilities]
 * @param max an optional maximum number of remote agents providing the given
 * [capabilities]
 * @author Michel Kraemer
 */
data class PoolAgentParams(
    val capabilities: List<String> = emptyList(),
    val min: Int = 0,
    val max: Int? = null
)
