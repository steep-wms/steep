package model.metadata

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * A range with a lower and an upper bound
 * @param min the lower bound
 * @param max the upper bound
 * @author Michel Kraemer
 */
data class Cardinality(val min: Int, val max: Int) {
  init {
    if (min > max) {
      throw IllegalArgumentException("`min' must be less than or equal to `max'")
    }
  }

  /**
   * Parses a cardinality from a string in the form `\[min..max\]` whereas `max`
   * may be `n`, which means there is no upper bound
   * @param str the string to parse
   */
  @JsonCreator
  constructor(str: String) : this(parseMin(str), parseMax(str))

  @JsonValue
  override fun toString(): String {
    return "[$min.." + (if (max == Int.MAX_VALUE) "n" else max) + "]"
  }

  companion object {
    /**
     * Parse the lower bound of a cardinality
     * @param str the string to parse
     * @return the lower bound
     */
    fun parseMin(str: String): Int {
      val a = str.trim().split("..")
      if (a.size != 2) {
        throw IllegalArgumentException("`cardinality' must have a minimum and a maximum")
      }
      val minStr = a[0].trim()
      if (minStr.isEmpty()) {
        throw IllegalArgumentException("`min' must not be empty")
      }
      return minStr.toInt()
    }

    /**
     * Parse the upper bound of a cardinality
     * @param str the string to parse
     * @return the upper bound
     */
    fun parseMax(str: String): Int {
      val a = str.trim().split("..")
      val maxStr = a[1].trim()
      if (maxStr.isEmpty()) {
        throw IllegalArgumentException("`max' must not be empty")
      }
      if (maxStr == "n") {
        return Int.MAX_VALUE
      }
      return maxStr.toInt()
    }
  }
}
