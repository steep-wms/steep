package model.setup

import ConfigConstants

/**
 * A setup describes how a virtual machine should be created
 * @param id the setup's unique ID
 * @param flavor the VM flavor
 * @param imageName the name of the VM image to deploy
 * @param availabilityZone the availability zone in which to create the VM
 * @param blockDeviceSizeGb the size of the VM's main block device in gigabytes
 * @param blockDeviceVolumeType the type of the VM's main block device (may be
 * `null` if the type should be selected automatically)
 * @param minVMs the minimum number of VMs to create with this setup
 * @param maxVMs the maximum number of VMs to create with this setup
 * @param maxCreateConcurrent the maximum number of VMs to create and provision
 * concurrently
 * @param provisioningScripts a list of scripts that should be executed after
 * the VM has been created to deploy software on it
 * @param providedCapabilities a list of capabilities that VMs with this setup
 * will have
 * @param sshUsername an optional username for the SSH connection to the
 * created VM (overrides [ConfigConstants.CLOUD_SSH_USERNAME])
 * @param additionalVolumes an optional list of [Volume]s that will be attached
 * to the VM
 * @param parameters arbitrary parameters that will be available through the
 * `setup` context object in provisioning script templates
 * @param creation an optional policy that defines rules for creating VMs from
 * this setup (default values for this parameter are defined in the `steep.yaml`)
 * @author Michel Kraemer
 */
data class Setup(
    val id: String,
    val flavor: String,
    val imageName: String,
    val availabilityZone: String,
    val blockDeviceSizeGb: Int,
    val blockDeviceVolumeType: String? = null,
    val minVMs: Int = 0,
    val maxVMs: Int,
    val maxCreateConcurrent: Int = 1,
    val provisioningScripts: List<String> = emptyList(),
    val providedCapabilities: List<String> = emptyList(),
    val sshUsername: String? = null,
    val additionalVolumes: List<Volume> = emptyList(),
    val parameters: Map<String, Any> = emptyMap(),
    val creation: CreationPolicy? = null
)
