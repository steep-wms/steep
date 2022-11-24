package model.processchain

import db.SubmissionRegistry
import java.time.Instant

/**
 * A process chain run
 * @author Michel Kraemer
 */
data class Run(
    /**
     * The point in time when the process chain run was started
     */
    val startTime: Instant,

    /**
     * The point in time when the process chain run has finished (if it has
     * finished yet)
     */
    val endTime: Instant? = null,

    /**
     * The status of the finished run or `null` if the run has not yet finished
     */
    val status: SubmissionRegistry.ProcessChainStatus? = null,

    /**
     * An optional error message if [status] equals
     * [SubmissionRegistry.ProcessChainStatus.ERROR]
     */
    val errorMessage: String? = null,

    /**
     * If the process chain's status is [SubmissionRegistry.ProcessChainStatus.PAUSED],
     * this field defines a point in time after which the process chain should
     * be automatically resumed. It is typically used in combination with a
     * [model.retry.RetryPolicy]: if the process chain run has failed and there
     * are still attempts left, this field defines when the next attempt should
     * be performed.
     */
    val autoResumeAfter: Instant? = null
)
