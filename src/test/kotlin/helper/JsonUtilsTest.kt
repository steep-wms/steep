package helper

import io.vertx.kotlin.core.json.jsonObjectOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [JsonUtils]
 * @author Michel Kraemer
 */
class JsonUtilsTest {
  /**
   * Test if a simple object can be merged
   */
  @Test
  fun simple() {
    val obj = jsonObjectOf(
        "type" to "Person",
        "person" to jsonObjectOf(
            "firstName" to "Clifford",
            "lastName" to "Thompson",
            "age" to 40,
            "address" to jsonObjectOf(
                "street" to "First Street",
                "number" to 6550
            )
        )
    )

    val expected = jsonObjectOf(
        "type" to "Person",
        "person.firstName" to "Clifford",
        "person.lastName" to "Thompson",
        "person.age" to 40,
        "person.address.street" to "First Street",
        "person.address.number" to 6550
    )

    assertThat(JsonUtils.flatten(obj)).isEqualTo(expected)
  }
}