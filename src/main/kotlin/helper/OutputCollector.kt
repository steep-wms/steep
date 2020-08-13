package helper

import java.util.LinkedList

/**
 * Collects lines from the output of a command called by [Shell] up to a
 * certain maximum number
 * @param maxLines the maximum number of lines to collect
 * @author Michel Kraemer
 */
class OutputCollector(private val maxLines: Int = 100) {
  private val lines = LinkedList<String>()

  /**
   * Collects a new [line]. Discards the oldest one if the maximum number of
   * lines has been reached.
   */
  fun collect(line: String) {
    lines.add(line)
    if (lines.size > maxLines) {
      lines.removeFirst()
    }
  }

  /**
   * Return the collected lines
   */
  fun lines(): List<String> {
    return lines
  }

  /**
   * Return all collected lines as a single string (i.e. the lines joined by
   * the line feed character)
   */
  fun output(): String = lines().joinToString("\n")
}
