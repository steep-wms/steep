package model.cloud

import helper.UniqueID
import model.setup.Setup
import java.time.Instant

/**
 * A virtual machine that has dynamically been created by the [cloud.CloudManager]
 * @param id a unique identifier
 * @param externalId the unique identifier of the VM returned by [cloud.CloudClient.createVM]
 * @param ipAddress the VM's IP address (if it has one)
 * @param setup the [Setup] for which the VM was created
 * @param creationTime the time when the VM was created
 * @param agentJoinTime the time when the remote agent on the VM joined the cluster
 * @param destructionTime the time when the VM was destroyed
 * @param status the current status of the VM
 * @param reason the reason why the VM has the current status (e.g. an error
 * message if it has the ERROR status or a simple message indicating why it has
 * been DESTROYED)
 * @author Michel Kraemer
 */
data class VM(
    val id: String = UniqueID.next(),
    val externalId: String? = null,
    val ipAddress: String? = null,
    val setup: Setup,
    val creationTime: Instant? = null,
    val agentJoinTime: Instant? = null,
    val destructionTime: Instant? = null,
    val status: Status = Status.CREATING,
    val reason: String? = null
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
