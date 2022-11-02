package cloud

import model.retry.RetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Tests for [VMCircuitBreaker]
 * @author Michel Kraemer
 */
class VMCircuitBreakerTest {
  /**
   * Check if the circuit breaker is open after the maximum number of
   * attempts has been reached
   */
  @Test
  fun openAfterAllAttemptsFailed() {
    var b = VMCircuitBreaker(RetryPolicy(2), 60 * 1000L)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(1)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(2)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse
  }

  /**
   * Ensure that successful attempts do not alter the circuit breaker
   */
  @Test
  fun successfulAttempts() {
    var b = VMCircuitBreaker(RetryPolicy(2), 60 * 1000L)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(true)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(true)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue
  }

  /**
   * Check if a successful attempt resets the circuit breaker
   */
  @Test
  fun resetAfterSuccess() {
    var b = VMCircuitBreaker(RetryPolicy(2), 60 * 1000L)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(1)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(true)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue
  }

  /**
   * Check if the circuit breaker goes into the half-open state after the given
   * reset timeout has passed and no other attempts have been made
   */
  @Test
  fun halfOpenAfterResetTimeout() {
    val today = LocalDate.of(2022, 10, 20)
    var cl = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    var b = VMCircuitBreaker(RetryPolicy(2), 60 * 1000L, clock = cl)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(2)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // jump forward in time but still before the timeout
    cl = Clock.offset(cl, Duration.ofSeconds(50))
    b = b.copy(clock = cl)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // jump forward in time after the timeout
    cl = Clock.offset(cl, Duration.ofSeconds(20))
    b = b.copy(clock = cl)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isTrue
    assertThat(b.canPerformAttempt).isTrue

    // circuit breaker is half open - perform another attempt that fails
    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(3)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    cl = Clock.offset(cl, Duration.ofSeconds(50))
    b = b.copy(clock = cl)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    cl = Clock.offset(cl, Duration.ofSeconds(20))
    b = b.copy(clock = cl)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isTrue
    assertThat(b.canPerformAttempt).isTrue

    // circuit breaker is half open - perform an attempt that succeeds
    b = b.afterAttemptPerformed(true)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue
  }

  /**
   * Test if the exponential delay is calculated correctly
   */
  @Test
  fun exponentialDelay() {
    var b = VMCircuitBreaker(RetryPolicy(4, 100, 2), 60 * 1000L)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.currentDelay).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(1)
    assertThat(b.currentDelay).isEqualTo(100)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(2)
    assertThat(b.currentDelay).isEqualTo(200)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(3)
    assertThat(b.currentDelay).isEqualTo(400)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(4)
    assertThat(b.currentDelay).isEqualTo(800)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // Pretend the circuit breaker was half open and perform another attempt
    // anyway. The delay should not increase!
    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(5) // > max attempts!
    assertThat(b.currentDelay).isEqualTo(800) // delay should still be at max attempts
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // repeat
    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(6) // > max attempts!
    assertThat(b.currentDelay).isEqualTo(800) // delay should still be at max attempts
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // Make a successful attempt. Delay should be reset.
    b = b.afterAttemptPerformed(true)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.currentDelay).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue
  }

  /**
   * Test if the exponential delay can be limited to a maximum
   */
  @Test
  fun exponentialDelayWithMaxDelay() {
    var b = VMCircuitBreaker(RetryPolicy(4, 100, 2, 400), 60 * 1000L)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.currentDelay).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(1)
    assertThat(b.currentDelay).isEqualTo(100)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(2)
    assertThat(b.currentDelay).isEqualTo(200)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(3)
    assertThat(b.currentDelay).isEqualTo(400)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue

    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(4)
    assertThat(b.currentDelay).isEqualTo(400)
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // Pretend the circuit breaker was half open and perform another attempt
    // anyway. The delay should not increase!
    b = b.afterAttemptPerformed(false)
    assertThat(b.performedAttempts).isEqualTo(5) // > max attempts!
    assertThat(b.currentDelay).isEqualTo(400) // delay should still be at maximum
    assertThat(b.open).isTrue
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isFalse

    // Make a successful attempt. Delay should be reset.
    b = b.afterAttemptPerformed(true)
    assertThat(b.performedAttempts).isEqualTo(0)
    assertThat(b.currentDelay).isEqualTo(0)
    assertThat(b.open).isFalse
    assertThat(b.halfOpen).isFalse
    assertThat(b.canPerformAttempt).isTrue
  }
}
