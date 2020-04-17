package db

import model.cloud.VM

/**
 * A registry for Cloud VMs created by Steep
 * @author Michel Kraemer
 */
interface VMRegistry {
  /**
   * Close the registry and release all resources
   */
  suspend fun close()

  /**
   * Add a [vm] to the registry
   */
  suspend fun addVM(vm: VM)

  /**
   * Get a list of all VMs in the registry
   * @param size the maximum number of VMs to return (may be negative
   * if all VMs should be returned)
   * @param offset the index of the first VM to return
   * @param order a positive number if the VMs should be returned in an
   * ascending order, negative otherwise
   * @return all VMs
   */
  suspend fun findVMs(size: Int = -1, offset: Int = 0, order: Int = 1): Collection<VM>

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
   * Get a list of VMs with a given [status]
   */
  suspend fun findVMsByStatus(status: VM.Status): Collection<VM>

  /**
   * Get a list of VMs that are not terminated (i.e. that don't have the
   * status [VM.Status.DESTROYED] or [VM.Status.ERROR])
   */
  suspend fun findNonTerminatedVMs(): Collection<VM>

  /**
   * Get the number of VMs with a given [setupId] that are not terminated (i.e.
   * that don't have the status [VM.Status.DESTROYED] or [VM.Status.ERROR])
   */
  suspend fun countNonTerminatedVMsBySetup(setupId: String): Long

  /**
   * Atomically set the status of the VM with the given [id] to [newStatus] if
   * and only if its current status is [currentStatus]. Returns `true` if the
   * status was [currentStatus] and is now [newStatus] or `false` if the status
   * was not [currentStatus].
   */
  suspend fun setVMStatus(id: String, currentStatus: VM.Status,
      newStatus: VM.Status): Boolean

  /**
   * Set the status of the VM with the given [id] to [newStatus] regardless of
   * the VM's current status. CAUTION: This method should only be used to, for
   * example, set the VM's status to [VM.Status.DESTROYING] or [VM.Status.ERROR]
   * when an error has occurred and the current status can be ignored. Otherwise,
   * consider using [setVMStatus] instead!
   */
  suspend fun forceSetVMStatus(id: String, newStatus: VM.Status)

  /**
   * Set the [externalId] of the VM with the given [id]
   */
  suspend fun setVMExternalID(id: String, externalId: String)

  /**
   * Set the [ipAddress] of the VM with the given [id]
   */
  suspend fun setVMIPAddress(id: String, ipAddress: String)

  /**
   * Set the [errorMessage] of the VM with the given [id]. If the [errorMessage]
   * is `null`, any existing value will be removed from the VM.
   */
  suspend fun setVMErrorMessage(id: String, errorMessage: String?)
}
