package model.setup

/**
 * Describes an additional volume that can be attached to a virtual machine
 * specified by a [Setup]
 * @param sizeGb the volume's size in gigabytes
 * @param type the volume's type (may be `null` if it should be selected
 * automatically)
 * @param availabilityZone the availability zone in which to create the volume
 * (may be `null` if the volume should be created in the same availability
 * zone as the VM to which it will be attached)
 */
data class Volume(
    val sizeGb: Int,
    val type: String? = null,
    val availabilityZone: String? = null
)
