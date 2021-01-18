package helper

import kotlinx.coroutines.delay
import model.retry.RetryPolicy
import org.slf4j.LoggerFactory
import java.util.concurrent.CancellationException
import kotlin.math.min
import kotlin.math.pow

private val log = LoggerFactory.getLogger("withRetry")

/**
 * Execute the given [block] with a retry [policy] (i.e. retry the execution
 * of the block until it succeeds or until the maximum number of attempts
 * specified in the retry policy has been reached). If the last attempt fails
 * with a throwable, the method will rethrow this and only this throwable.
 * Throwables from previous attempts will not be recorded.
 */
suspend fun <R> withRetry(policy: RetryPolicy?, block: suspend (attempt: Int) -> R): R? {
  if (policy == null) {
    return block(1)
  }

  var attempt = 0
  var lastThrowable: Throwable? = null
  while (policy.maxAttempts == -1 || attempt < policy.maxAttempts) {
    val failedMsg = when {
      policy.maxAttempts > 0 ->
        "Operation failed $attempt out of ${policy.maxAttempts} times."
      attempt > 1 ->
        "Operation failed $attempt times."
      else ->
        "Operation failed $attempt time."
    }

    if (attempt > 0 && policy.delay > 0) {
      var actualDelay = (policy.delay * policy.exponentialBackoff.toDouble().pow(attempt - 1))
          .coerceAtMost(Long.MAX_VALUE.toDouble())
          .toLong()
      if (policy.maxDelay != null) {
        actualDelay = min(actualDelay, policy.maxDelay)
      }

      if (lastThrowable != null) {
        if (lastThrowable is CancellationException) {
          // Job has been cancelled. Stop without retrying.
          break
        }

        log.error("$failedMsg Retrying after $actualDelay milliseconds.")
        log.trace("Cause", lastThrowable)
      }

      delay(actualDelay)
    } else {
      if (lastThrowable != null) {
        if (lastThrowable is CancellationException) {
          // Job has been cancelled. Stop without retrying.
          break
        }

        log.error("$failedMsg Retrying now ...")
        log.trace("Cause", lastThrowable)
      }
    }

    try {
      return block(attempt + 1)
    } catch (t: Throwable) {
      lastThrowable = t
    }

    attempt++
  }

  if (lastThrowable != null) {
    throw lastThrowable
  }

  return null
}
