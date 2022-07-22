/**
 * Constants for event bus addresses
 * @author Michel Kraemer
 */
object AddressConstants {
  /**
   * Make the controller look for new submissions now
   */
  const val CONTROLLER_LOOKUP_NOW = "steep.controller.lookupNow"

  /**
   * Make the controller look for orphaned running submissions now
   */
  const val CONTROLLER_LOOKUP_ORPHANS_NOW = "steep.controller.lookupOrphansNow"

  /**
   * Make the scheduler look for new process chains now
   */
  const val SCHEDULER_LOOKUP_NOW = "steep.scheduler.lookupNow"

  /**
   * Make the scheduler look for orphaned running process chains now
   */
  const val SCHEDULER_LOOKUP_ORPHANS_NOW = "steep.scheduler.lookupOrphansNow"

  /**
   * Prefix for eventbus addresses of [Scheduler]s. Each scheduler listens to
   * one of these addresses. The actual address is [SCHEDULER_PREFIX] + (ID of
   * the scheduler's primary remote agent) + SUFFIX for the operation.
   */
  const val SCHEDULER_PREFIX = "steep.scheduler."

  /**
   * Eventbus address suffix to get the IDs of all process chains a scheduler
   * is currently executing. The actual address is [SCHEDULER_PREFIX] + (ID of
   * the scheduler's primary remote agent) + this SUFFIX.
   */
  const val SCHEDULER_RUNNING_PROCESS_CHAINS_SUFFIX = ".runningProcessChains"

  /**
   * Will be published when a new cluster node has been added
   */
  const val CLUSTER_NODE_ADDED = "steep.cluster.nodeAdded"

  /**
   * Will be published when a cluster node has left
   */
  const val CLUSTER_NODE_LEFT = "steep.cluster.nodeLeft"

  /**
   * Will be published when a node's data has been merged into an existing cluster
   */
  const val CLUSTER_LIFECYCLE_MERGED = "steep.cluster.lifecycleMerged"

  /**
   * Will be published when a remote agent has been added
   */
  const val REMOTE_AGENT_ADDED = "steep.remoteAgentRegistry.agentAdded"

  /**
   * Will be published when a remote agent has left
   */
  const val REMOTE_AGENT_LEFT = "steep.remoteAgentRegistry.agentLeft"

  /**
   * Will be published if the remote agent registry was not able to allocate
   * an agent with the given set of capabilities
   */
  const val REMOTE_AGENT_MISSING = "steep.remoteAgentRegistry.agentMissing"

  /**
   * Will be published by a remote agent when it becomes busy
   */
  const val REMOTE_AGENT_BUSY = "steep.remoteAgentRegistry.agentBusy"

  /**
   * Will be published by a remote agent when it becomes idle
   */
  const val REMOTE_AGENT_IDLE = "steep.remoteAgentRegistry.agentIdle"

  /**
   * Prefix for eventbus addresses of [agent.RemoteAgent]s. Each remote agent
   * listens to one of these addresses. The actual address is
   * [REMOTE_AGENT_ADDRESS_PREFIX] + ID of the remote agent.
   */
  const val REMOTE_AGENT_ADDRESS_PREFIX = "steep.remoteAgentRegistry.agent."

  /**
   * Suffix for eventbus addresses of [agent.RemoteAgent]s that are able to
   * provide process chain logs. The actual address is
   * [REMOTE_AGENT_ADDRESS_PREFIX] + agentId + [REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX].
   */
  const val REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX = ".logs.processchains"

  /**
   * Prefix for an eventbus endpoint that receives live log messages of
   * a running process chain. The actual address is
   * [LOGS_PROCESSCHAINS_PREFIX] + ID of the process chain. Log messages will
   * only be sent if [ConfigConstants.LOGS_PROCESSCHAINS_ENABLED] is `true`
   */
  const val LOGS_PROCESSCHAINS_PREFIX = "steep.logs.processchains."

  /**
   * Prefix for addresses of local agents. Each local agent listens to one
   * of these addresses while it executes a process chain. The actual
   * address is [LOCAL_AGENT_ADDRESS_PREFIX] + ID of the process chain.
   */
  const val LOCAL_AGENT_ADDRESS_PREFIX = "steep.localAgent."

  /**
   * Will be published when a submission has been added to the registry
   */
  const val SUBMISSION_ADDED = "steep.submissionRegistry.submissionAdded"

  /**
   * Will be published when a submission's start time has changed
   */
  const val SUBMISSION_STARTTIME_CHANGED = "steep.submissionRegistry.submissionStartTimeChanged"

  /**
   * Will be published when a submission's end time has changed
   */
  const val SUBMISSION_ENDTIME_CHANGED = "steep.submissionRegistry.submissionEndTimeChanged"

  /**
   * Will be published when a submission's status has changed
   */
  const val SUBMISSION_STATUS_CHANGED = "steep.submissionRegistry.submissionStatusChanged"

  /**
   * Will be published when a submission's priority has changed. Does not mean
   * that the priorities of the process chains belonging to this submission have
   * also changed. Listen to [PROCESSCHAIN_ALL_PRIORITY_CHANGED] for this.
   */
  const val SUBMISSION_PRIORITY_CHANGED = "steep.submissionRegistry.submissionPriorityChanged"

  /**
   * Will be published when a submission's error message has changed
   */
  const val SUBMISSION_ERRORMESSAGE_CHANGED = "steep.submissionRegistry.submissionErrorMessageChanged"

  /**
   * Will be published when submissions have been deleted
   */
  const val SUBMISSIONS_DELETED = "steep.submissionRegistry.submissionsDeleted"

  /**
   * Will be published when process chains have been added to the registry
   */
  const val PROCESSCHAINS_ADDED = "steep.submissionRegistry.processChainsAdded"

  /**
   * Similar to [PROCESSCHAINS_ADDED] but only the number of the added process
   * chains will be transferred and not the process chains themselves
   */
  const val PROCESSCHAINS_ADDED_SIZE = "steep.submissionRegistry.processChainsAddedSize"

  /**
   * Will be published when a process chain's start time has changed
   */
  const val PROCESSCHAIN_STARTTIME_CHANGED = "steep.submissionRegistry.processChainStartTimeChanged"

  /**
   * Will be published when a process chain's end time has changed
   */
  const val PROCESSCHAIN_ENDTIME_CHANGED = "steep.submissionRegistry.processChainEndTimeChanged"

  /**
   * Will be published when a process chain's status has changed
   */
  const val PROCESSCHAIN_STATUS_CHANGED = "steep.submissionRegistry.processChainStatusChanged"

  /**
   * Will be published when the status of multiple process chains has changed at once
   */
  const val PROCESSCHAIN_ALL_STATUS_CHANGED = "steep.submissionRegistry.processChainAllStatusChanged"

  /**
   * Will be published when a process chain's priority has changed
   */
  const val PROCESSCHAIN_PRIORITY_CHANGED = "steep.submissionRegistry.processChainPriorityChanged"

  /**
   * Will be published when the priority of multiple process chains has changed at once
   */
  const val PROCESSCHAIN_ALL_PRIORITY_CHANGED = "steep.submissionRegistry.processChainAllPriorityChanged"

  /**
   * Will be published when a process chain's results have changed
   */
  const val PROCESSCHAIN_RESULTS_CHANGED = "steep.submissionRegistry.processChainResultsChanged"

  /**
   * Will be published when a process chain's error message have changed
   */
  const val PROCESSCHAIN_ERRORMESSAGE_CHANGED = "steep.submissionRegistry.processChainErrorMessageChanged"

  /**
   * Will be published when a process chain is currently running and its
   * estimated progress has changed
   */
  const val PROCESSCHAIN_PROGRESS_CHANGED = "steep.submissionRegistry.processChainProgressChanged"

  /**
   * Will be published when a VM has been added to the registry
   */
  const val VM_ADDED = "steep.vmRegistry.vmAdded"

  /**
   * Will be published when a VM's creation time has changed
   */
  const val VM_CREATIONTIME_CHANGED = "steep.vmRegistry.vmCreationTimeChanged"

  /**
   * Will be published when the time a remote agent on a VM joined the cluster has changed
   */
  const val VM_AGENTJOINTIME_CHANGED = "steep.vmRegistry.vmAgentJoinTimeChanged"

  /**
   * Will be published when a VM's destruction time has changed
   */
  const val VM_DESTRUCTIONTIME_CHANGED = "steep.vmRegistry.vmDestructionTimeChanged"

  /**
   * Will be published when a VM's status has changed
   */
  const val VM_STATUS_CHANGED = "steep.vmRegistry.vmStatusChanged"

  /**
   * Will be published when a VM's external ID has changed
   */
  const val VM_EXTERNALID_CHANGED = "steep.vmRegistry.vmExternalIdChanged"

  /**
   * Will be published when a VM's IP address has changed
   */
  const val VM_IPADDRESS_CHANGED = "steep.vmRegistry.vmIpAddressChanged"

  /**
   * Will be published when the reason for the status of a VM's has changed
   */
  const val VM_REASON_CHANGED = "steep.vmRegistry.vmReasonChanged"

  /**
   * Will be published when VMs have been deleted
   */
  const val VMS_DELETED = "steep.vmRegistry.vmsDeleted"
}
