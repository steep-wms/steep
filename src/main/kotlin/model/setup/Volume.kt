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
 * @param deviceName the device name under which the volume will be exposed to
 * the operating system on the virtual machine (e.g. `/dev/sdb`). Please note
 * that this value is mandatory for some cloud providers, while for others, it
 * is optional and they automatically assign a name. Also, some cloud providers
 * only accept names that correspond to a specific scheme. Please refer to your
 * cloud provider's documentation on whether specifying a name is mandatory and
 * which values are considered valid.
 */
data class Volume(
    val sizeGb: Int,
    val type: String? = null,
    val availabilityZone: String? = null,
    val deviceName: String? = null
)
