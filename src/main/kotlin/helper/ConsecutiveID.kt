package helper

/**
 * Generates consecutive IDs
 * @author Michel Kraemer
 */
class ConsecutiveID(start: Long = 0L) : IDGenerator {
  private var current = start

  override fun next(): String {
    val r = current.toString()
    ++current
    return r
  }
}
