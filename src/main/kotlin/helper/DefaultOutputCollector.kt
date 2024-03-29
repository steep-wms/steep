package helper

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Collects lines from the output of a command called by [Shell] up to a
 * certain maximum number
 * @param maxLines the maximum number of lines to collect
 * @author Michel Kraemer
 */
open class DefaultOutputCollector(private val maxLines: Int = 100) : OutputCollector {
  private val lines = ConcurrentLinkedDeque<String>()

  /**
   * Collects a new [line]. Discards the oldest one if the maximum number of
   * lines has been reached.
   */
  override fun collect(line: String) {
    lines.add(line)
    while (lines.size > maxLines) {
      lines.removeFirst()
    }
  }

  override fun lines() = lines.toList()

  override fun output(): String = lines().joinToString("\n")
}
