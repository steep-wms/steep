package cloud.template

import model.setup.Volume

/**
 * A volume attached to a virtual machine
 */
data class AttachedVolume(
    /**
     * The volume's ID
     */
    val volumeId: String,

    /**
     * The volume descriptor from the virtual machine's [model.setup.Setup]
     */
    val descriptor: Volume
)
