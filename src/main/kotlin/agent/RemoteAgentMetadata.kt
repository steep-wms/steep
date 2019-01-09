package agent

import helper.UniqueID

/**
 * Metadata about a remote agent
 * @param id the agent's ID
 * @param nodeId the ID of the cluster node the agent runs on
 * @param capabilities a set of capabilities the agent has
 */
data class RemoteAgentMetadata(
    val id: String = UniqueID.next(),
    val nodeId: String,
    val capabilities: Set<String> = emptySet()
)
