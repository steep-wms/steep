package model.cloud

import helper.UniqueID
import model.setup.Setup

/**
 * A virtual machine that has dynamically been created by the [cloud.CloudManager]
 * @param id a unique identifier
 * @param externalId the unique identifier of the VM returned by [cloud.CloudClient.createVM]
 * @param ipAddress the VM's IP address (if it has one)
 * @param setup the [Setup] for which the VM was created
 * @param status the current status of the VM
 * @author Michel Kraemer
 */
data class VM(
    val id: String = UniqueID.next(),
    val externalId: String? = null,
    val ipAddress: String? = null,
    val setup: Setup,
    val status: Status = Status.CREATING,
    val errorMessage: String? = null
) {
  enum class Status {
    /**
     * The VM is currently being created
     */
    CREATING,

    /**
     * The VM has been created and is currently being provisioned
     */
    PROVISIONING,

    /**
     * The VM has been created and provisioned successfully. It is currently
     * running and registered as a remote agent.
     */
    RUNNING,

    /**
     * The remote agent on this VM has left. It will be destroyed eventually.
     */
    LEFT,

    /**
     * The VM is currently being destroyed
     */
    DESTROYING,

    /**
     * The VM has been destroyed
     */
    DESTROYED,

    /**
     * The VM could not be created, provisioned, or failed otherwise
     */
    ERROR
  }
}
