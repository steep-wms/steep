package model.retry

import helper.JsonUtils
import io.vertx.core.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [RetryPolicy]
 * @author Michel Kraemer
 */
class RetryPolicyTest {
  /**
   * Test if human-readable delays can be parsed
   */
  @Test
  fun humanReadableDuration() {
    val s1 = """{
      |  "maxAttempts": 5,
      |  "delay": "5m 30s",
      |  "exponentialBackoff": 2,
      |  "maxDelay": "1d"
      |}
    """.trimMargin()

    val s2 = """{
      |  "maxAttempts": 3,
      |  "delay": 1000,
      |  "exponentialBackoff": 3
      |}
    """.trimMargin()

    assertThat(JsonUtils.fromJson<RetryPolicy>(JsonObject(s1))).isEqualTo(RetryPolicy(
        maxAttempts = 5,
        delay = (5L * 60 + 30) * 1000,
        exponentialBackoff = 2,
        maxDelay = 1L * 24 * 60 * 60 * 1000
    ))

    assertThat(JsonUtils.fromJson<RetryPolicy>(JsonObject(s2))).isEqualTo(RetryPolicy(
        maxAttempts = 3,
        delay = 1000,
        exponentialBackoff = 3
    ))
  }

  /**
   * Test if the exponential delay is calculated correctly
   */
  @Test
  fun exponentialDelay() {
    val p = RetryPolicy(4, 100, 2)
    assertThat(p.calculateDelay(0)).isEqualTo(0)
    assertThat(p.calculateDelay(1)).isEqualTo(100)
    assertThat(p.calculateDelay(2)).isEqualTo(200)
    assertThat(p.calculateDelay(3)).isEqualTo(400)
    assertThat(p.calculateDelay(4)).isEqualTo(800)
    assertThat(p.calculateDelay(5)).isEqualTo(800) // delay should still be at max attempts
    assertThat(p.calculateDelay(6)).isEqualTo(800) // delay should still be at max attempts
  }

  /**
   * Test if the exponential delay is calculated correctly if
   * [RetryPolicy.maxAttempts] is `0`
   */
  @Test
  fun noMaxAttempts() {
    val p = RetryPolicy(0, 100, 2)
    assertThat(p.calculateDelay(0)).isEqualTo(0)
    assertThat(p.calculateDelay(1)).isEqualTo(0)
    assertThat(p.calculateDelay(2)).isEqualTo(0)
  }

  /**
   * Test if the exponential delay is calculated correctly for infinite attempts
   */
  @Test
  fun infiniteAttempts() {
    val p = RetryPolicy(-1, 100, 2)
    assertThat(p.calculateDelay(0)).isEqualTo(0)
    assertThat(p.calculateDelay(1)).isEqualTo(100)
    assertThat(p.calculateDelay(2)).isEqualTo(200)
    assertThat(p.calculateDelay(3)).isEqualTo(400)
    assertThat(p.calculateDelay(4)).isEqualTo(800)
    assertThat(p.calculateDelay(5)).isEqualTo(1600)
    assertThat(p.calculateDelay(6)).isEqualTo(3200)
  }

  /**
   * Test if the exponential delay can be limited to a maximum
   */
  @Test
  fun exponentialDelayWithMaxDelay() {
    val p = RetryPolicy(4, 100, 2, 400)
    assertThat(p.calculateDelay(0)).isEqualTo(0)
    assertThat(p.calculateDelay(1)).isEqualTo(100)
    assertThat(p.calculateDelay(2)).isEqualTo(200)
    assertThat(p.calculateDelay(3)).isEqualTo(400)
    assertThat(p.calculateDelay(4)).isEqualTo(400)
    assertThat(p.calculateDelay(5)).isEqualTo(400)
  }
}
