package helper

import kotlinx.coroutines.delay
import model.retry.RetryPolicy
import kotlin.math.min
import kotlin.math.pow

/**
 * Execute the given [block] with a retry [policy] (i.e. retry the execution
 * of the block until it succeeds or until the maximum number of attempts
 * specified in the retry policy has been reached). If the last attempt fails
 * with a throwable, the method will rethrow this and only this throwable.
 * Throwables from previous attempts will not be recorded.
 */
suspend fun <R> withRetry(policy: RetryPolicy?, block: suspend () -> R): R? {
  if (policy == null) {
    return block()
  }

  var attempt = 0
  var lastThrowable: Throwable? = null
  while (policy.maxAttempts == -1 || attempt < policy.maxAttempts) {
    if (attempt > 0 && policy.delay > 0) {
      var actualDelay = (policy.delay * policy.exponentialBackoff.toDouble().pow(attempt - 1))
          .coerceAtMost(Long.MAX_VALUE.toDouble())
          .toLong()
      if (policy.maxDelay != null) {
        actualDelay = min(actualDelay, policy.maxDelay)
      }
      delay(actualDelay)
    }

    try {
      return block()
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
