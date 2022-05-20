package search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import search.QuotedStringSplitter.split

/**
 * Test [QuotedStringSplitter]
 * @author Michel Kraemer
 */
class QuotedStringSplitterTest {
  @Test
  fun split() {
    assertThat(split("hello world")).isEqualTo(listOf(
        "hello" to false,
        "world" to false
    ))

    assertThat(split("  hello    world ")).isEqualTo(listOf(
        "hello" to false,
        "world" to false
    ))

    assertThat(split("\"hello world\" test")).isEqualTo(listOf(
        "hello world" to true,
        "test" to false
    ))

    assertThat(split("\" hello   world\" test")).isEqualTo(listOf(
        " hello   world" to true,
        "test" to false
    ))

    assertThat(split("'hello world' test")).isEqualTo(listOf(
        "hello world" to true,
        "test" to false
    ))

    assertThat(split("' hello   world' test")).isEqualTo(listOf(
        " hello   world" to true,
        "test" to false
    ))

    assertThat(split("that's cool")).isEqualTo(listOf(
        "that's" to false,
        "cool" to false
    ))

    assertThat(split("\"'s g'\" \"s' \" '\"s' 's\"'")).isEqualTo(listOf(
        "'s g'" to true,
        "s' " to true,
        "\"s" to true,
        "s\"" to true
    ))

    assertThat(split("'a\\'b' \"a\\\"b\"")).isEqualTo(listOf(
        "a'b" to true,
        "a\"b" to true
    ))

    assertThat(split("in:name name:\"foobar\" id:'don\\'t'")).isEqualTo(listOf(
        "in:name" to false,
        "name:\"foobar\"" to false,
        "id:'don\\'t'" to false
    ))
  }
}
