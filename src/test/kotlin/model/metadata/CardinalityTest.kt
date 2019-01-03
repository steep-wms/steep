package model.metadata

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests for [Cardinality]
 * @author Michel Kraemer
 */
class CardinalityTest {
  /**
   * Test a simple range
   */
  @Test
  fun simpleRange() {
    val c = Cardinality(0, 1)
    assertThat(c.min).isEqualTo(0)
    assertThat(c.max).isEqualTo(1)
  }

  /**
   * Test a cardinality of 1
   */
  @Test
  fun one() {
    val c = Cardinality(1, 1)
    assertThat(c.min).isEqualTo(1)
    assertThat(c.max).isEqualTo(1)
  }

  /**
   * Test a cardinality of 0
   */
  @Test
  fun zero() {
    val c = Cardinality(0, 0)
    assertThat(c.min).isEqualTo(0)
    assertThat(c.max).isEqualTo(0)
  }

  /**
   * Test a range from 0 to unlimited
   */
  @Test
  fun unlimited() {
    val c = Cardinality(0, Int.MAX_VALUE)
    assertThat(c.min).isEqualTo(0)
    assertThat(c.max).isEqualTo(Int.MAX_VALUE)
  }

  /**
   * Test if the constructor throws if `max` is less than `min`
   */
  @Test
  fun maxLessThanMin() {
    assertThatThrownBy { Cardinality(1, 0) }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Test if a string with a simple range can be parsed
   */
  @Test
  fun fromStringSimpleRange() {
    val c = Cardinality("0..1")
    assertThat(c).isEqualTo(Cardinality(0, 1))
  }

  /**
   * Test if a string with a cardinality of 1 can be parsed
   */
  @Test
  fun fromStringOne() {
    val c = Cardinality("1..1")
    assertThat(c).isEqualTo(Cardinality(1, 1))
  }

  /**
   * Test if a string with a cardinality of 0 can be parsed
   */
  @Test
  fun fromStringZero() {
    val c = Cardinality("0..0")
    assertThat(c).isEqualTo(Cardinality(0, 0))
  }

  /**
   * Test if a string with a cardinality of 0 to unlimited can be parsed
   */
  @Test
  fun fromStringUnlimited() {
    val c = Cardinality("0..n")
    assertThat(c).isEqualTo(Cardinality(0, Int.MAX_VALUE))
  }

  /**
   * Test if the constructor throws if `max` is less than `min` in a string
   */
  @Test
  fun fromStringMaxLessThanMin() {
    assertThatThrownBy { Cardinality("1..0") }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Test if the constructor throws if `min` is invalid in string
   */
  @Test
  fun fromStringInvalidMin() {
    assertThatThrownBy { Cardinality("n..n") }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Test if the constructor throws if `min` is missing in string
   */
  @Test
  fun fromStringMissingMin() {
    assertThatThrownBy { Cardinality("..n") }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Test if the constructor throws if `max` is missing in string
   */
  @Test
  fun fromStringMissingMax() {
    assertThatThrownBy { Cardinality("1..") }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Test if the constructor throws if string is not a range
   */
  @Test
  fun fromStringInvalidRange() {
    assertThatThrownBy { Cardinality("1") }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  /**
   * Test if a cardinality can be converted to a string
   */
  @Test
  fun toStr() {
    assertThat(Cardinality(0, 1).toString()).isEqualTo("0..1")
    assertThat(Cardinality(1, 1).toString()).isEqualTo("1..1")
    assertThat(Cardinality(0, 0).toString()).isEqualTo("0..0")
    assertThat(Cardinality(0, Int.MAX_VALUE).toString()).isEqualTo("0..n")
  }
}
