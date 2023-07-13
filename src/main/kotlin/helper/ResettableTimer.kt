package helper

import io.vertx.core.Vertx
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A one-shot timer that fires after [timeoutMilliseconds] and calls the
 * given [callback]. The timer can be [reset] before it has fired.
 * @author Michel Kraemer
 */
class ResettableTimer(
    private val vertx: Vertx,
    private val timeoutMilliseconds: Long,
    private val callback: () -> Unit
) {
  private val lastReset = AtomicLong()

  private var timerId: Long
  private var timerIdLock = ReentrantLock()

  init {
    lastReset.set(System.currentTimeMillis())
    timerId = vertx.setTimer(timeoutMilliseconds, this::handler)
  }

  private fun handler(timerId: Long) {
    // check if we have reached the deadline
    val elapsed = System.currentTimeMillis() - lastReset.get()
    if (elapsed >= timeoutMilliseconds) {
      // deadline has been reached - invoke callback
      callback()
    } else {
      // timeout was extended in the meantime - start a new timer for the
      // remaining milliseconds
      timerIdLock.withLock {
        if (timerId == this.timerId) {
          this.timerId = vertx.setTimer(timeoutMilliseconds - elapsed, this::handler)
        }
      }
    }
  }

  /**
   * Reset the timer
   */
  fun reset() {
    lastReset.set(System.currentTimeMillis())
  }

  /**
   * Cancel the timer
   */
  fun cancel() {
    timerIdLock.withLock {
      vertx.cancelTimer(timerId)
      timerId = 0L
    }
  }
}
