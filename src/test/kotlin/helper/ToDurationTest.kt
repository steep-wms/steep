package helper

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests the [toDuration] function
 * @author Michel Kraemer
 */
class ToDurationTest {
  /**
   * Test if milliseconds can be parsed
   */
  @Test
  fun milliseconds() {
    assertThat("333 milliseconds".toDuration()).isEqualTo(Duration.ofMillis(333))
    assertThat("1 millisecond".toDuration()).isEqualTo(Duration.ofMillis(1))
    assertThat("100 millis".toDuration()).isEqualTo(Duration.ofMillis(100))
    assertThat("250 milli".toDuration()).isEqualTo(Duration.ofMillis(250))
    assertThat("1000ms".toDuration()).isEqualTo(Duration.ofMillis(1000))
  }

  /**
   * Test if seconds can be parsed
   */
  @Test
  fun seconds() {
    assertThat("333 seconds".toDuration()).isEqualTo(Duration.ofSeconds(333))
    assertThat("1 second".toDuration()).isEqualTo(Duration.ofSeconds(1))
    assertThat("100 secs".toDuration()).isEqualTo(Duration.ofSeconds(100))
    assertThat("250 sec".toDuration()).isEqualTo(Duration.ofSeconds(250))
    assertThat("1000s".toDuration()).isEqualTo(Duration.ofSeconds(1000))
  }

  /**
   * Test if minutes can be parsed
   */
  @Test
  fun minutes() {
    assertThat("333 minutes".toDuration()).isEqualTo(Duration.ofMinutes(333))
    assertThat("1 minute".toDuration()).isEqualTo(Duration.ofMinutes(1))
    assertThat("100 mins".toDuration()).isEqualTo(Duration.ofMinutes(100))
    assertThat("250 min".toDuration()).isEqualTo(Duration.ofMinutes(250))
    assertThat("1000m".toDuration()).isEqualTo(Duration.ofMinutes(1000))
  }

  /**
   * Test if hours can be parsed
   */
  @Test
  fun hours() {
    assertThat("333 hours".toDuration()).isEqualTo(Duration.ofHours(333))
    assertThat("1 hour".toDuration()).isEqualTo(Duration.ofHours(1))
    assertThat("100 hrs".toDuration()).isEqualTo(Duration.ofHours(100))
    assertThat("250 hr".toDuration()).isEqualTo(Duration.ofHours(250))
    assertThat("1000h".toDuration()).isEqualTo(Duration.ofHours(1000))
  }

  /**
   * Test if days can be parsed
   */
  @Test
  fun days() {
    assertThat("333 days".toDuration()).isEqualTo(Duration.ofDays(333))
    assertThat("1 day".toDuration()).isEqualTo(Duration.ofDays(1))
    assertThat("100d".toDuration()).isEqualTo(Duration.ofDays(100))
  }

  /**
   * Test if whitespace characters are ignored
   */
  @Test
  fun whitespace() {
    assertThat("1 mins".toDuration()).isEqualTo(Duration.ofMinutes(1))
    assertThat("1mins".toDuration()).isEqualTo(Duration.ofMinutes(1))
    assertThat("1    mins".toDuration()).isEqualTo(Duration.ofMinutes(1))
    assertThat("  1 mins".toDuration()).isEqualTo(Duration.ofMinutes(1))
    assertThat("1 mins  ".toDuration()).isEqualTo(Duration.ofMinutes(1))
    assertThat("  1    mins    ".toDuration()).isEqualTo(Duration.ofMinutes(1))
  }

  /**
   * Test if strings with mixed durations can be parsed
   */
  @Test
  fun mixed() {
    assertThat("10h 30 minutes".toDuration()).isEqualTo(Duration.ofMinutes(10L * 60 + 30))
    assertThat("1 hour 10minutes 5s".toDuration()).isEqualTo(
        Duration.ofSeconds(1L * 60 * 60 + 10 * 60 + 5))
    assertThat("1d 5h".toDuration()).isEqualTo(Duration.ofHours(24L + 5))
    assertThat("5h 1d".toDuration()).isEqualTo(Duration.ofHours(24L + 5))
    assertThat("  1d     5h ".toDuration()).isEqualTo(Duration.ofHours(24L + 5))
    assertThat("10 days 1hrs 30m 15 secs".toDuration()).isEqualTo(
        Duration.ofSeconds(10L * 24 * 60 * 60 + 1 * 60 * 60 + 30 * 60 + 15))
    assertThat("10days1hrs30m15secs".toDuration()).isEqualTo(
        Duration.ofSeconds(10L * 24 * 60 * 60 + 1 * 60 * 60 + 30 * 60 + 15))
  }

  /**
   * Test that empty strings are rejected
   */
  @Test
  fun empty() {
    assertThatThrownBy { "".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Invalid duration")
    assertThatThrownBy { "  ".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Expected number at position 2")
  }

  /**
   * Test that invalid strings are rejected
   */
  @Test
  fun invalid() {
    assertThatThrownBy { "1 Elvis".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("Unknown duration unit: `Elvis'")
    assertThatThrownBy { "100".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("Expected unit at position 3")
    assertThatThrownBy { "Elvis".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("Expected number at position 0")
    assertThatThrownBy { "100 days 1 Elvis".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("Unknown duration unit: `Elvis'")
    assertThatThrownBy { "100 days 1".toDuration() }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessage("Expected unit at position 10")
  }
}
