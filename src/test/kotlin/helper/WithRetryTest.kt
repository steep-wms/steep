package helper

import assertThatThrownBy
import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.retry.RetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [withRetry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class WithRetryTest {
  /**
   * A test object with one method that counts how many times it has been
   * called and at which points in time
   */
  private class Obj(private val maxFails: Int) {
    var attempts = 0
    var timestamps = mutableListOf<Long>()

    fun doSomething(attempt: Int) {
      attempts++
      assertThat(attempt).isEqualTo(attempts)
      timestamps.add(System.currentTimeMillis())
      if (attempts <= maxFails) {
        throw IllegalStateException("Attempt $attempts")
      }
    }
  }

  /**
   * Test successful execution without a test policy
   */
  @Test
  fun noPolicy(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(0)
      val start = System.currentTimeMillis()
      withRetry(null, o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(1)
        assertThat(o.timestamps).allSatisfy { assertThat(it).isLessThan(start + 100) }
        assertThat(end).isLessThan(o.timestamps.last() + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test faulty execution without a test policy
   */
  @Test
  fun noPolicyFault(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(1)
      ctx.coVerify {
        val start = System.currentTimeMillis()
        assertThatThrownBy { withRetry(null, o::doSomething) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Attempt 1")
        val end = System.currentTimeMillis()

        assertThat(o.attempts).isEqualTo(1)
        assertThat(o.timestamps).allSatisfy { assertThat(it).isLessThan(start + 100) }
        assertThat(end).isLessThan(o.timestamps.last() + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution after three attempts without delay
   */
  @Test
  fun noDelay(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(2)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 3), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(3)
        assertThat(o.timestamps).allSatisfy { assertThat(it).isLessThan(start + 10) }
        assertThat(end).isLessThan(start + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test faulty execution after three attempts without delay
   */
  @Test
  fun noDelayFault(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(3)
      ctx.coVerify {
        val start = System.currentTimeMillis()
        assertThatThrownBy { withRetry(RetryPolicy(maxAttempts = 3), o::doSomething) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Attempt 3")
        val end = System.currentTimeMillis()

        assertThat(o.attempts).isEqualTo(3)
        assertThat(o.timestamps).allSatisfy { assertThat(it).isLessThan(start + 10) }
        assertThat(end).isLessThan(start + 50)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution before the maximum number of attempts without delay
   */
  @Test
  fun noDelayEarly(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(1)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 3), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(2)
        assertThat(o.timestamps).allSatisfy { assertThat(it).isLessThan(start + 10) }
        assertThat(end).isLessThan(start + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution with a constant delay
   */
  @Test
  fun constantDelay(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(3)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 4, delay = 100), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(4)
        assertThat(o.timestamps[0]).isLessThan(start + 10)
        assertThat(o.timestamps[1]).isBetween(start + 100, start + 110)
        assertThat(o.timestamps[2]).isBetween(o.timestamps[1] + 100, o.timestamps[1] + 110)
        assertThat(o.timestamps[3]).isBetween(o.timestamps[2] + 100, o.timestamps[2] + 110)
        assertThat(end).isLessThan(o.timestamps[3] + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution with exponential backoff (factor 2)
   */
  @Test
  fun exponentialBackoff(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(4)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 5, delay = 100, exponentialBackoff = 2), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(5)
        assertThat(o.timestamps[0]).isLessThan(start + 10)
        assertThat(o.timestamps[1]).isBetween(o.timestamps[0] + 100, o.timestamps[0] + 110)
        assertThat(o.timestamps[2]).isBetween(o.timestamps[1] + 200, o.timestamps[1] + 210)
        assertThat(o.timestamps[3]).isBetween(o.timestamps[2] + 400, o.timestamps[2] + 410)
        assertThat(o.timestamps[4]).isBetween(o.timestamps[3] + 800, o.timestamps[3] + 810)
        assertThat(end).isLessThan(o.timestamps[4] + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution with exponential backoff (factor 3)
   */
  @Test
  fun exponentialBackoff3(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(3)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 4, delay = 100, exponentialBackoff = 3), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(4)
        assertThat(o.timestamps[0]).isLessThan(start + 10)
        assertThat(o.timestamps[1]).isBetween(o.timestamps[0] + 100, o.timestamps[0] + 150)
        assertThat(o.timestamps[2]).isBetween(o.timestamps[1] + 300, o.timestamps[1] + 310)
        assertThat(o.timestamps[3]).isBetween(o.timestamps[2] + 900, o.timestamps[2] + 910)
        assertThat(end).isLessThan(o.timestamps[3] + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution with exponential backoff and maximum delay
   */
  @Test
  fun exponentialBackoffMaxDelay(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(5)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 6, delay = 100, exponentialBackoff = 2,
          maxDelay = 500), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(6)
        assertThat(o.timestamps[0]).isLessThan(start + 10)
        assertThat(o.timestamps[1]).isBetween(o.timestamps[0] + 100, o.timestamps[0] + 110)
        assertThat(o.timestamps[2]).isBetween(o.timestamps[1] + 200, o.timestamps[1] + 210)
        assertThat(o.timestamps[3]).isBetween(o.timestamps[2] + 400, o.timestamps[2] + 410)
        assertThat(o.timestamps[4]).isBetween(o.timestamps[3] + 500, o.timestamps[3] + 510)
        assertThat(o.timestamps[5]).isBetween(o.timestamps[4] + 500, o.timestamps[4] + 510)
        assertThat(end).isLessThan(o.timestamps[5] + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test successful execution before the maximum number of attempts with
   * exponential backoff
   */
  @Test
  fun exponentialBackoffEarly(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(2)
      val start = System.currentTimeMillis()
      withRetry(RetryPolicy(maxAttempts = 6, delay = 100, exponentialBackoff = 2,
          maxDelay = 500), o::doSomething)
      val end = System.currentTimeMillis()

      ctx.verify {
        assertThat(o.attempts).isEqualTo(3)
        assertThat(o.timestamps[0]).isLessThan(start + 10)
        assertThat(o.timestamps[1]).isBetween(o.timestamps[0] + 100, o.timestamps[0] + 110)
        assertThat(o.timestamps[2]).isBetween(o.timestamps[1] + 200, o.timestamps[1] + 210)
        assertThat(end).isLessThan(o.timestamps[2] + 10)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test faulty execution with exponential backoff
   */
  @Test
  fun exponentialBackoffFault(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val o = Obj(4)
      ctx.coVerify {
        val start = System.currentTimeMillis()
        assertThatThrownBy { withRetry(RetryPolicy(maxAttempts = 4, delay = 100,
            exponentialBackoff = 2), o::doSomething) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Attempt 4")
        val end = System.currentTimeMillis()

        assertThat(o.attempts).isEqualTo(4)
        assertThat(o.timestamps[0]).isLessThan(start + 50)
        assertThat(o.timestamps[1]).isBetween(o.timestamps[0] + 100, o.timestamps[0] + 110)
        assertThat(o.timestamps[2]).isBetween(o.timestamps[1] + 200, o.timestamps[1] + 210)
        assertThat(o.timestamps[3]).isBetween(o.timestamps[2] + 400, o.timestamps[2] + 410)
        assertThat(end).isLessThan(o.timestamps[3] + 50)
      }

      ctx.completeNow()
    }
  }
}
