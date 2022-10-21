package model.setup

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import helper.StringDurationToMillisecondsConverter
import model.retry.RetryPolicy

/**
 * A policy that defines rules for creating VMs from a certain [Setup]
 * @author Michel Kraemer
 */
data class CreationPolicy(
    /**
     * Specifies how many attempts should be made to create a VM (if creation
     * fails) as well as possible (exponential) delays between those attempts.
     *
     * If `null`, default values from the `steep.yaml` file will be used.
     */
    val retries: RetryPolicy? = null,

    /**
     * When the maximum number of attempts to create a VM (specified by [retries]
     * has been reached) the [Setup] will be locked and no other VM with this
     * setup will be created. This parameter defines how long it will be locked
     * (in milliseconds).
     *
     * If `null`, the default value from the `steep.yaml` file will be used.
     */
    @JsonDeserialize(converter = StringDurationToMillisecondsConverter::class)
    val lockAfterRetries: Long? = null
)
