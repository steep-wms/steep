package cloud

/**
 * Provides methods to access different Cloud APIs
 * @author Michel Kraemer
 */
interface CloudClient {
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
   * @param imageId the ID of the virtual machine image to deploy to the
   * block device
   * @param blockDeviceSizeGb the size of the block device in GB
   * @param volumeType the type of the volume (may be `null` if the type
   * should be selected automatically)
   * @param metadata the metadata to attach to the block device
   * @return the block device ID
   */
  suspend fun createBlockDevice(imageId: String, blockDeviceSizeGb: Int,
      volumeType: String?, metadata: Map<String, String>): String

  /**
   * Destroy a block device
   * @param id the block device ID
   */
  suspend fun destroyBlockDevice(id: String)

  /**
   * Create a virtual machine. The virtual machine will be available when the
   * method returns.
   * @param flavor the flavor to use
   * @param blockDeviceId the ID of the block device to attach
   * @param metadata the metadata to attach to the virtual machine
   * @return the ID of the new virtual machine
   */
  suspend fun createVM(flavor: String, blockDeviceId: String,
      metadata: Map<String, String>): String

  /**
   * Wait for a virtual machine to be available (e.g. after it has been created
   * or after it was restarted, etc.)
   * @param vmId the ID of the virtual machine
   */
  suspend fun waitForVM(vmId: String)

  /**
   * Destroy a virtual machine
   * @param id the ID of the virtual machine
   */
  suspend fun destroyVM(id: String)

  /**
   * Get the IP address of a virtual machine
   * @param vmId the ID of the virtual machine
   * @return the IP address
   */
  suspend fun getIPAddress(vmId: String): String
}
