package helper

/**
 * Parses HTTP Range headers
 * @author Michel Kraemer
 */
object RangeParser {
  private val RANGE_REGEX = """bytes=(\d*)-(\d*)""".toRegex()

  /**
   * Parse a given [rangeHeader] and return start and end values
   */
  fun parse(rangeHeader: String): Pair<Long?, Long?>? {
    val match = RANGE_REGEX.matchEntire(rangeHeader) ?: return null

    val start = match.groupValues[1].let { if (it.isEmpty()) null else it.toLong() }
    val end = match.groupValues[2].let { if (it.isEmpty()) null else it.toLong() }

    return if (start == null && end != null) {
      (-end to null)
    } else {
      (start to end)
    }
  }
}
