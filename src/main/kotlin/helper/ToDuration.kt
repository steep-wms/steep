package helper

import java.time.Duration

/**
 * Parses a human-readable duration.
 *
 * A duration string consists of one or more number/unit pairs possibly
 * separated by whitespace characters. Supported units are:
 *
 * * `milliseconds`, `millisecond`, `millis`, `milli`, `ms`
 * * `seconds`, `second`, `secs`, `sec`, `s`
 * * `minutes`, `minute`, `mins`, `min`, `m`
 * * `hours`, `hour`, `hrs`, `hr`, `h`
 * * `days`, `day`, `d`
 *
 * Numbers must be positive integers (up to [Long.MAX_VALUE]).
 *
 * Examples:
 *
 *    1000ms
 *    3 secs
 *    5m
 *    20mins
 *    10h 30 minutes
 *    1 hour 10minutes 5s
 *    1d 5h
 *    10 days 1hrs 30m 15 secs
 */
fun String.toDuration(): Duration {
  var i = 0

  fun skipWhitespace() {
    while (i < this.length && Character.isWhitespace(this[i])) ++i
  }

  fun parseNumber(): Long {
    var j = i
    while (j < this.length && Character.isDigit(this[j])) ++j
    if (j == i) {
      throw IllegalArgumentException("Expected number at position $i")
    }
    val r = this.substring(i, j).toLong()
    i = j
    return r
  }

  fun parseUnit(): String {
    var j = i
    while (j < this.length && (this[j] in 'a'..'z' || this[j] in 'A'..'Z')) ++j
    if (j == i) {
      throw IllegalArgumentException("Expected unit at position $i")
    }
    val r = this.substring(i, j)
    i = j
    return r
  }

  var result: Duration? = null
  while (i < this.length) {
    skipWhitespace()
    val n = parseNumber()
    skipWhitespace()
    val u = parseUnit()
    skipWhitespace()

    val d = when (u) {
      "milliseconds", "millisecond", "millis", "milli", "ms" -> Duration.ofMillis(n)
      "seconds", "second", "secs", "sec", "s" -> Duration.ofSeconds(n)
      "minutes", "minute", "mins", "min", "m" -> Duration.ofMinutes(n)
      "hours", "hour", "hrs", "hr", "h" -> Duration.ofHours(n)
      "days", "day", "d" -> Duration.ofDays(n)
      else -> throw IllegalArgumentException("Unknown duration unit: `$u'")
    }

    result = (result ?: Duration.ZERO) + d
  }

  if (result == null) {
    throw IllegalArgumentException("Invalid duration: `$this'")
  }

  return result
}
