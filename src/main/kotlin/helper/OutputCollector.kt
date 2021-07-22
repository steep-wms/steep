package helper

/**
 * Collects lines from the output of a command called by [Shell]
 * @author Michel Kraemer
 */
interface OutputCollector {
  /**
   * Collects a new [line]. Discards the oldest one if the maximum number of
   * lines has been reached.
   */
  fun collect(line: String)

  /**
   * Return a copy of the list of collected lines
   */
  fun lines(): List<String>

  /**
   * Return all collected lines as a single string (i.e. the lines joined by
   * the line feed character)
   */
  fun output(): String
}
