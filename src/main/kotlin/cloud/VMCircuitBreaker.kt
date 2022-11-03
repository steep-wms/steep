package cloud

import model.retry.RetryPolicy
import java.time.Clock
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/**
 * A use-case driven implementation of the circuit breaker pattern that can
 * be used to decide whether a VM of a certain [model.setup.Setup] can be
 * created or not and how long the creation should be delayed before the next
 * attempt.
 *
 * The circuit breaker has a [retryPolicy] that defines the maximum number of
 * attempts to create the VM as well as possible (exponential) delays between
 * attempts. If the maximum number of attempts has been reached, the circuit
 * breaker goes into the *open* state, which means no other attempts should
 * be performed.
 *
 * After a certain [resetTimeout] has been reached, the circuit breaker goes
 * into the *half open* state. This means that one more attempt can be
 * performed. If this attempt is successful, the circuit breaker goes into the
 * *closed* state and resets itself to the initial values. If not, it
 * immediately switches back to the *open* state and delays between attempts
 * stay the same.
 *
 * The class is immutable. The [afterAttemptPerformed] can be used to switch
 * from one state to another. It creates a new circuit breaker instance.
 *
 * @author Michel Kraemer
 */
data class VMCircuitBreaker(
    /**
     * A retry policy that defines the maximum number of attempts that can be
     * performed before the circuit breaker opens
     */
    val retryPolicy: RetryPolicy,

    /**
     * A reset timeout (in milliseconds) that defines when the circuit breaker
     * should go into the *half open* state after is was open
     */
    val resetTimeout: Long,

    /**
     * A counter for the number of performed attempts
     */
    val performedAttempts: Int = 0,

    /**
     * The clock to use to determine if the the reset timeout has been reached
     */
    private val clock: Clock = Clock.systemUTC(),

    /**
     * An internal timestamp that records when the circuit breaker's state
     * has last changed
     */
    private val lastAttemptTimestamp: Instant = Instant.now(clock)
) {
  /**
   * `true` if the circuit breaker is in the *open* state and no other attempts
   * should be performed
   */
  val open = performedAttempts >= retryPolicy.maxAttempts

  /**
   * `true` if the circuit breaker is half open and one more attempt can be
   * performed
   */
  val halfOpen get() = Instant.now(clock).isAfter(
      lastAttemptTimestamp.plusMillis(resetTimeout))

  /**
   * `true` if an attempt can be performed
   */
  val canPerformAttempt get() = !open || halfOpen

  /**
   * The delay in milliseconds before the next attempt can be performed
   */
  val currentDelay = if (performedAttempts > 0) {
    // never calculate a delay that is longer than the one for the maximum number of attempts
    val lpa = min(retryPolicy.maxAttempts, performedAttempts)
    min(retryPolicy.delay * retryPolicy.exponentialBackoff.toDouble().pow(lpa - 1).toLong(),
        retryPolicy.maxDelay ?: Long.MAX_VALUE)
  } else {
    0L
  }

  /**
   * This method should be called after each attempt. It updates the circuit
   * breaker's state based on whether the attempt was [success]ful or not. It
   * returns a new circuit breaker instance.
   */
  fun afterAttemptPerformed(success: Boolean): VMCircuitBreaker {
    return VMCircuitBreaker(
        retryPolicy = retryPolicy,
        resetTimeout = resetTimeout,
        performedAttempts = if (success) 0 else performedAttempts + 1,
        clock = clock
    )
  }
}
