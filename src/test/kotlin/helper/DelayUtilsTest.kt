package helper

import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for the delay utils
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class DelayUtilsTest {
  /**
   * Test if [abortableDelay] waits correctly if not aborted
   */
  @Test
  fun abortableDelay(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val start = System.currentTimeMillis()
      abortableDelay<Unit>(1000, "ADDRESS")
      val end = System.currentTimeMillis()
      ctx.verify {
        assertThat(end - start).isGreaterThanOrEqualTo(1000)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if [abortableDelay] aborts
   */
  @Test
  fun abortableDelayAbort(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val start = System.currentTimeMillis()
      val result = abortableDelay<String>(10000, "ADDRESS")
      val end = System.currentTimeMillis()
      ctx.verify {
        assertThat(end - start).isLessThan(5000)
        assertThat(result).isEqualTo("MY MESSAGE")
      }
      ctx.completeNow()
    }
    vertx.setTimer(200) {
      vertx.eventBus().send("ADDRESS", "MY MESSAGE")
    }
  }
}
