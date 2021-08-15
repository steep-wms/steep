package db

import model.cloud.VM
import java.time.Instant

/**
 * A registry for Cloud VMs created by Steep
 * @author Michel Kraemer
 */
interface VMRegistry : Registry {
  /**
   * Add a [vm] to the registry
   */
  suspend fun addVM(vm: VM)

  /**
   * Get a list of all VMs in the registry
   * @param status an optional status the VMs should have
   * @param size the maximum number of VMs to return (may be negative
   * if all VMs should be returned)
   * @param offset the index of the first VM to return
   * @param order a positive number if the VMs should be returned in an
   * ascending order, negative otherwise
   * @return all VMs
   */
  suspend fun findVMs(status: VM.Status? = null, size: Int = -1,
      offset: Int = 0, order: Int = 1): Collection<VM>

  /**
   * Get a single VM with a given [id] from the registry or return `null` if
   * there is no such VM
   */
  suspend fun findVMById(id: String): VM?

  /**
   * Get a single VM with a given [externalId] from the registry or return
   * `null` if there is no such VM
   */
  suspend fun findVMByExternalId(externalId: String): VM?

  /**
   * Get a list of VMs that are not terminated (i.e. that don't have the
   * status [VM.Status.DESTROYED] or [VM.Status.ERROR])
   */
  suspend fun findNonTerminatedVMs(): Collection<VM>

  /**
   * Get the total number of VMs in the registry, optionally count only those
   * with a given [status]
   */
  suspend fun countVMs(status: VM.Status? = null): Long

  /**
   * Get the number of VMs with a given [setupId] that are not terminated (i.e.
   * that don't have the status [VM.Status.DESTROYED] or [VM.Status.ERROR])
   */
  suspend fun countNonTerminatedVMsBySetup(setupId: String): Long

  /**
   * Get the number of VMs with a given [setupId] that are currently being
   * started (i.e. that have the status [VM.Status.CREATING] or [VM.Status.PROVISIONING])
   */
  suspend fun countStartingVMsBySetup(setupId: String): Long

  /**
   * Set the [creationTime] of the VM with the given [id] (i.e. the time the
   * VM was created)
   */
  suspend fun setVMCreationTime(id: String, creationTime: Instant)

  /**
   * Set the [agentJoinTime] of the VM with the given [id] (i.e the time
   * when the remote agent on the VM joined the cluster)
   */
  suspend fun setVMAgentJoinTime(id: String, agentJoinTime: Instant)

  /**
   * Set the [destructionTime] of the VM with the given [id] (i.e. the time
   * the VM was destroyed)
   */
  suspend fun setVMDestructionTime(id: String, destructionTime: Instant)

  /**
   * Atomically set the status of the VM with the given [id] to [newStatus] if
   * and only if its current status is [currentStatus].
   */
  suspend fun setVMStatus(id: String, currentStatus: VM.Status, newStatus: VM.Status)

  /**
   * Set the status of the VM with the given [id] to [newStatus] regardless of
   * the VM's current status. CAUTION: This method should only be used to, for
   * example, set the VM's status to [VM.Status.DESTROYING] or [VM.Status.ERROR]
   * when an error has occurred and the current status can be ignored. Otherwise,
   * consider using [setVMStatus] instead!
   */
  suspend fun forceSetVMStatus(id: String, newStatus: VM.Status)

  /**
   * Get the status of the VM with the given [id]
   */
  suspend fun getVMStatus(id: String): VM.Status

  /**
   * Set the [externalId] of the VM with the given [id]
   */
  suspend fun setVMExternalID(id: String, externalId: String)

  /**
   * Set the [ipAddress] of the VM with the given [id]
   */
  suspend fun setVMIPAddress(id: String, ipAddress: String)

  /**
   * Set the [reason] for the current status of the VM with the given [id]. If
   * the [reason] is `null`, any existing value will be removed from the VM.
   */
  suspend fun setVMReason(id: String, reason: String?)

  /**
   * Delete all VMs that have been destroyed before the given [timestamp]
   * (regardless of their status) and return their IDs
   */
  suspend fun deleteVMsDestroyedBefore(timestamp: Instant): Collection<String>
}
