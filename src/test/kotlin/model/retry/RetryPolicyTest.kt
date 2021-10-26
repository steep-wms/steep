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
}
