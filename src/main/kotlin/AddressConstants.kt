/**
 * Constants for event bus addresses
 * @author Michel Kraemer
 */
object AddressConstants {
  /**
   * Make the controller look up for new submissions now
   */
  const val CONTROLLER_LOOKUP_NOW = "jobmanager.controller.lookupNow"

  /**
   * Make the controller look up for orphaned running submissions now
   */
  const val CONTROLLER_LOOKUP_ORPHANS_NOW = "jobmanager.controller.lookupOrphansNow"

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

  /**
   * Will be published if the remote agent registry was not able to allocate
   * an agent with the given set of capabilities
   */
  const val REMOTE_AGENT_MISSING = "jobmanager.remoteAgentRegistry.agentMissing"

  /**
   * Will be published by a remote agent when it becomes busy
   */
  const val REMOTE_AGENT_BUSY = "jobmanager.remoteAgentRegistry.agentBusy"

  /**
   * Will be published by a remote agent when it becomes idle
   */
  const val REMOTE_AGENT_IDLE = "jobmanager.remoteAgentRegistry.agentIdle"

  /**
   * Prefix for eventbus addresses of [agent.RemoteAgent]s. Each remote agent
   * listens to one of these addresses. The actual address is
   * [REMOTE_AGENT_ADDRESS_PREFIX] + ID of the remote agent.
   */
  const val REMOTE_AGENT_ADDRESS_PREFIX = "jobmanager.remoteAgentRegistry.agent."

  /**
   * Prefix for addresses of local agents. Each local agent listens to one
   * of these addresses while it executes a process chain. The actual
   * address is [LOCAL_AGENT_ADDRESS_PREFIX] + ID of the process chain.
   */
  const val LOCAL_AGENT_ADDRESS_PREFIX = "jobmanager.localAgent."

  /**
   * Will be published when a submission has been added to the registry
   */
  const val SUBMISSION_ADDED = "jobmanager.submissionRegistry.submissionAdded"

  /**
   * Will be published when a submission's start time has changed
   */
  const val SUBMISSION_STARTTIME_CHANGED = "jobmanager.submissionRegistry.submissionStartTimeChanged"

  /**
   * Will be published when a submission's end time has changed
   */
  const val SUBMISSION_ENDTIME_CHANGED = "jobmanager.submissionRegistry.submissionEndTimeChanged"

  /**
   * Will be published when a submission's status has changed
   */
  const val SUBMISSION_STATUS_CHANGED = "jobmanager.submissionRegistry.submissionStatusChanged"

  /**
   * Will be published when a submission's error message has changed
   */
  const val SUBMISSION_ERRORMESSAGE_CHANGED = "jobmanager.submissionRegistry.submissionErrorMessageChanged"

  /**
   * Will be published when process chains have been added to the registry
   */
  const val PROCESSCHAINS_ADDED = "jobmanager.submissionRegistry.processChainsAdded"

  /**
   * Similar to [PROCESSCHAINS_ADDED] but only the number of the added process
   * chains will be transferred and not the process chains themselves
   */
  const val PROCESSCHAINS_ADDED_SIZE = "jobmanager.submissionRegistry.processChainsAddedSize"

  /**
   * Will be published when a process chain's start time has changed
   */
  const val PROCESSCHAIN_STARTTIME_CHANGED = "jobmanager.submissionRegistry.processChainStartTimeChanged"

  /**
   * Will be published when a process chain's end time has changed
   */
  const val PROCESSCHAIN_ENDTIME_CHANGED = "jobmanager.submissionRegistry.processChainEndTimeChanged"

  /**
   * Will be published when a process chain's status has changed
   */
  const val PROCESSCHAIN_STATUS_CHANGED = "jobmanager.submissionRegistry.processChainStatusChanged"

  /**
   * Will be published when the status of multiple process chains has changed at once
   */
  const val PROCESSCHAIN_ALL_STATUS_CHANGED = "jobmanager.submissionRegistry.processChainAllStatusChanged"

  /**
   * Will be published when a process chain's results have changed
   */
  const val PROCESSCHAIN_RESULTS_CHANGED = "jobmanager.submissionRegistry.processChainResultsChanged"

  /**
   * Will be published when a process chain's error message have changed
   */
  const val PROCESSCHAIN_ERRORMESSAGE_CHANGED = "jobmanager.submissionRegistry.processChainErrorMessageChanged"
}
