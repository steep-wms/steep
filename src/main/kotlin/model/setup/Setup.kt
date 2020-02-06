package model.setup

/**
 * A setup describes how a virtual machine should be created
 * @param id the setup's unique ID
 * @param flavor the VM flavor
 * @param imageName the name of the VM image to deploy
 * @param availabilityZone the availability zone in which to create the VM
 * @param blockDeviceSizeGb the size of the VM's block device in gigabytes
 * @param blockDeviceVolumeType the type of the VM's block device (may be
 * `null` if the type should be selected automatically)
 * @param maxVMs the maximum number of VMs to create with this setup
 * @param provisioningScripts a list of scripts that should be executed after
 * the VM has been created to deploy software on it
 * @param providedCapabilities a list of capabilities that VMs with this setup
 * will have
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
    val provisioningScripts: List<String> = emptyList(),
    val providedCapabilities: List<String> = emptyList()
)
