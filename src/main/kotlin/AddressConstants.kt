/**
 * Constants for event bus addresses
 * @author Michel Kraemer
 */
object AddressConstants {
  /**
   * Make the scheduler look up for new process chains now
   */
  const val SCHEDULER_LOOKUP_NOW = "jobmanager.scheduler.lookupNow"

  /**
   * Will be published when a new cluster node has been added
   */
  const val CLUSTER_NODE_ADDED = "jobmanager.cluster.nodeAdded"

  /**
   * Will be published when a cluster node has left
   */
  const val CLUSTER_NODE_LEFT = "jobmanager.cluster.nodeLeft"

  /**
   * Will be published when a remote agent has been added
   */
  const val REMOTE_AGENT_ADDED = "jobmanager.remoteAgentRegistry.agentAdded"

  /**
   * Will be published when a remote agent has left
   */
  const val REMOTE_AGENT_LEFT = "jobmanager.remoteAgentRegistry.agentLeft"
}
