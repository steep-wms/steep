package helper

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RangeParserTest {
  /**
   * Test invalid range headers
   */
  @Test
  fun invalid() {
    assertThat(RangeParser.parse("")).isNull()
    assertThat(RangeParser.parse("foobar")).isNull()
    assertThat(RangeParser.parse("bytes=")).isNull()
    assertThat(RangeParser.parse("bytes=132")).isNull()
    assertThat(RangeParser.parse("bytes=aaa-")).isNull()
    assertThat(RangeParser.parse("bytes=132-aaa")).isNull()
    assertThat(RangeParser.parse("bytes=-aaa")).isNull()
  }

  /**
   * Test a partial range header
   */
  @Test
  fun partial() {
    assertThat(RangeParser.parse("bytes=132-")).isEqualTo(Pair(132L, null))
    assertThat(RangeParser.parse("bytes=-132")).isEqualTo(Pair(-132L, null))
  }

  /**
   * Test a full range header
   */
  @Test
  fun full() {
    assertThat(RangeParser.parse("bytes=132-1024")).isEqualTo(Pair(132L, 1024L))
  }
}
