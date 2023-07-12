package helper

import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [ResettableTimer]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class ResettableTimerTest {
  /**
   * Test if the timer works correctly
   */
  @Test
  fun normalTimeout(vertx: Vertx, ctx: VertxTestContext) {
    val start = System.currentTimeMillis()
    ResettableTimer(vertx, 100) {
      val elapsed = System.currentTimeMillis() - start
      ctx.verify {
        assertThat(elapsed).isGreaterThanOrEqualTo(100)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the timer can be reset
   */
  @Test
  fun reset(vertx: Vertx, ctx: VertxTestContext) {
    val start = System.currentTimeMillis()
    CoroutineScope(vertx.dispatcher()).launch {
      val timer = ResettableTimer(vertx, 100) {
        val elapsed = System.currentTimeMillis() - start
        ctx.verify {
          assertThat(elapsed).isGreaterThanOrEqualTo(150)
        }
        ctx.completeNow()
      }
      delay(50)
      timer.reset()
    }
  }

  /**
   * Test if the timer can be reset multiple times
   */
  @Test
  fun resetMultiple(vertx: Vertx, ctx: VertxTestContext) {
    val start = System.currentTimeMillis()
    CoroutineScope(vertx.dispatcher()).launch {
      val timer = ResettableTimer(vertx, 100) {
        val elapsed = System.currentTimeMillis() - start
        ctx.verify {
          assertThat(elapsed).isGreaterThanOrEqualTo(250)
        }
        ctx.completeNow()
      }
      delay(50)
      timer.reset()
      delay(50)
      timer.reset()
      delay(50)
      timer.reset()
    }
  }

  /**
   * Test if the timer can be canceled
   */
  @Test
  fun cancel(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val timer = ResettableTimer(vertx, 100) {
        ctx.failNow("Timer should not trigger!")
      }
      delay(50)
      timer.cancel()
      delay(100)
      ctx.completeNow()
    }
  }
}
