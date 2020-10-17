package helper

import com.fasterxml.jackson.databind.util.StdConverter

/**
 * JSON deserializer that converts a string containing a human-readable
 * duration to milliseconds
 * @author Michel Kraemer
 */
class StringDurationToMillisecondsConverter : StdConverter<String, Long>() {
  override fun convert(value: String): Long {
    return value.toLongOrNull() ?: value.toDuration().toMillis()
  }
}
