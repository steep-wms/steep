package helper

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test

/**
 * Tests for [UniqueID]
 * @author Michel Kraemer
 */
class UniqueIDTest {
  @Test
  fun toMillis() {
    val now = System.currentTimeMillis()
    val id = UniqueID.next()
    val millis = UniqueID.toMillis(id)
    assertThat(millis).isCloseTo(now, Offset.offset(1000))
  }
}
