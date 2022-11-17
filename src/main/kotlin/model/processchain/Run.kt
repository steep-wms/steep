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
    val errorMessage: String? = null
)
