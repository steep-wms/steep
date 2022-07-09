package cloud

import java.time.Duration

/**
 * Provides methods to access different Cloud APIs
 * @author Michel Kraemer
 */
interface CloudClient {
  /**
   * Get a list of block devices that match the given metadata filter. Only
   * available block devices will be returned!
   * @param metadataFilter A filter that will be called with the metadata of
   * each available block device. If the filter returns `true` the block
   * device's ID will be included in the method's result
   * @return the list of matching, available block devices
   */
  suspend fun listAvailableBlockDevices(metadataFilter: (suspend (Map<String, String>) ->
      Boolean)? = null): List<String>

  /**
   * Get a list of virtual machines that match the given metadata filter
   * @param metadataFilter A filter that will be called with the metadata of
   * each available virtual machine. If the filter returns `true` the virtual
   * machine's ID will be included in the method's result
   * @return the list of matching virtual machines
   */
  suspend fun listVMs(metadataFilter: ((Map<String, String>) -> Boolean)? = null): List<String>

  /**
   * Get the unique identifier of a virtual machine image
   * @param name the human-readable image name
   * @return the image ID
   */
  suspend fun getImageID(name: String): String

  /**
   * Create a block device. The block device will be available to be mounted
   * when the method returns.
   * @param blockDeviceSizeGb the size of the block device in GB
   * @param volumeType the type of the volume (may be `null` if the type
   * should be selected automatically)
   * @param imageId the ID of the virtual machine image to deploy to the
   * block device (may be `null` if the device should be empty)
   * @param bootable `true` if the device should be bootable so it can act
   * as the main/primary volume of a VM
   * @param availabilityZone the availability zone in which to create the block
   * device
   * @param metadata the metadata to attach to the block device
   * @return the block device ID
   */
  suspend fun createBlockDevice(blockDeviceSizeGb: Int, volumeType: String?,
      imageId: String?, bootable: Boolean, availabilityZone: String,
      metadata: Map<String, String>): String

  /**
   * Destroy a block device
   * @param id the block device ID
   */
  suspend fun destroyBlockDevice(id: String)

  /**
   * Create a virtual machine. The virtual machine will not be available when
   * the method returns. You should use [waitForVM] to wait for it to become
   * available or [isVMActive] to poll its state.
   * @param name the new virtual machine's name
   * @param flavor the flavor to use
   * @param blockDeviceId the ID of the block device to attach
   * @param availabilityZone the availability zone in which to create the VM
   * @param metadata the metadata to attach to the virtual machine
   * @return the ID of the new virtual machine
   */
  suspend fun createVM(name: String, flavor: String, blockDeviceId: String,
      availabilityZone: String, metadata: Map<String, String>): String

  /**
   * Check if the VM with the given ID is active
   * @param vmId the ID of the virtual machine
   * @return `true` if the VM is active, `false` otherwise
   */
  suspend fun isVMActive(vmId: String): Boolean

  /**
   * Wait for a virtual machine to be available (e.g. after it has been created
   * or after it was restarted, etc.)
   * @param vmId the ID of the virtual machine
   * @param timeout the maximum time to wait
   */
  suspend fun waitForVM(vmId: String, timeout: Duration? = null)

  /**
   * Destroy a virtual machine
   * @param id the ID of the virtual machine
   * @param timeout the maximum time to wait for the VM to be destroyed
   */
  suspend fun destroyVM(id: String, timeout: Duration? = null)

  /**
   * Get the IP address of a virtual machine
   * @param vmId the ID of the virtual machine
   * @return the IP address
   */
  suspend fun getIPAddress(vmId: String): String

  /**
   * Attach a volume to a virtual machine
   * @param vmId the ID of the virtual machine
   * @param volumeId the ID of the volume to attach
   */
  suspend fun attachVolume(vmId: String, volumeId: String)
}
