package model.retry

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import helper.StringDurationToMillisecondsConverter
import kotlin.math.min
import kotlin.math.pow

/**
 * Defines rules for retrying operations such as the execution of
 * [model.workflow.Action]s or [model.processchain.Executable]s in case
 * an error has happened.
 * @author Michel Kraemer
 */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
data class RetryPolicy(
    /**
     * The maximum number of attempts to perform. This includes the initial
     * attempt. For example, a value of `3` means 1 initial attempt and 2
     * retries. The default value is `1`. A value of `-1` means an unlimited
     * (infinite) number of attempts. `0` means there will be no attempt at
     * all (the operation will be skipped).
     */
    val maxAttempts: Int = 1,

    /**
     * The number of milliseconds that should pass between two attempts.
     * The default is `0`, which means the operation will be retried
     * immediately.
     */
    @JsonDeserialize(converter = StringDurationToMillisecondsConverter::class)
    val delay: Long = 0,

    /**
     * A factor for an exponential backoff. The actual [delay] between two
     * attempts will be calculated as follows:
     *
     *     actualDelay = min(delay * pow(exponentialBackoff, nAttempt - 1), maxDelay)
     *
     * For example, if [delay] equals 1000, [exponentialBackoff] equals 2, and
     * [maxDelay] equals 10000 then the following actual delays will apply:
     *
     * * Delay after attempt 1: min(1000 * pow(2, 0), 10000) = 1000
     * * Delay after attempt 2: min(1000 * pow(2, 1), 10000) = 2000
     * * Delay after attempt 3: min(1000 * pow(2, 2), 10000) = 4000
     * * Delay after attempt 4: min(1000 * pow(2, 3), 10000) = 8000
     * * Delay after attempt 5: min(1000 * pow(2, 4), 10000) = 10000
     * * Delay after attempt 6: min(1000 * pow(2, 4), 10000) = 10000
     *
     * The default value is `1`, which means there is no backoff and the actual
     * delay always equals [delay].
     */
    val exponentialBackoff: Int = 1,

    /**
     * The maximum number of milliseconds that should pass between two attempts.
     * Only applies if [exponentialBackoff] is larger than `1`. The default
     * value is `null`, which means there is no upper limit
     */
    @JsonDeserialize(converter = StringDurationToMillisecondsConverter::class)
    val maxDelay: Long? = null
) {
  /**
   * Calculate delay in milliseconds after [performedAttempts] and before
   * the next attempt
   */
  fun calculateDelay(performedAttempts: Int): Long {
    return if (performedAttempts > 0) {
      // never calculate a delay that is longer than the one for the maximum number of attempts
      val lpa = if (maxAttempts < 0) performedAttempts else min(maxAttempts, performedAttempts)
      min(delay * exponentialBackoff.toDouble().pow(lpa - 1).toLong(),
          maxDelay ?: Long.MAX_VALUE)
    } else {
      0L
    }
  }
}
