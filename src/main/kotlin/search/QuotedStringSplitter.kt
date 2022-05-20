package search

import org.apache.commons.text.StringEscapeUtils

/**
 * Splits strings around whitespace characters. Takes care of double-quotes
 * and single-quotes. Trims and unescapes the results.
 * @author Michel Kraemer
 */
object QuotedStringSplitter {
  private val pattern = """"((\\"|[^"])*)"|'((\\'|[^'])*)'|(\S+)""".toRegex()

  /**
   * Splits strings around whitespace characters. Also returns a flag denoting
   * whether the string part was quoted or not.
   *
   * Example:
   *
   *     input string: "Hello World"
   *     output:       [("Hello", false), ("World", false)]
   *
   * Takes care of double-quotes and single-quotes.
   *
   * Example:
   *
   *     input string: "\"Hello World\" 'cool'"
   *     output:       [("Hello World", true), ("cool", true)]
   *
   * Trims and unescapes the results.
   *
   * Example:
   *
   *     input string: "  Hello   \"Wo\\\"rld\"  "
   *     output:       [("Hello", false), ("Wo\"rld", true)]
   */
  fun split(str: String): List<Pair<String, Boolean>> {
    return pattern.findAll(str).map { m ->
      val r = m.groups[1]?.let { it.value to true } ?:
        m.groups[3]?.let { it.value to true } ?: (m.value to false)
      if (r.second) {
        StringEscapeUtils.unescapeJava(r.first) to r.second
      } else {
        r
      }
    }.toList()
  }
}
