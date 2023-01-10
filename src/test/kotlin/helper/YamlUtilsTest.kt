package helper

import io.pebbletemplates.pebble.error.RootAttributeNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * Tests for YAML Utilities
 * @author Michel Kraemer
 */
class YamlUtilsTest {
  @Test
  fun loadPlain() {
    val m = Yaml().loadTemplate<Map<String, Any>>("""
      foo: bar {{ value }}
    """.trimIndent(), emptyMap())
    assertThat(m).isEqualTo(mapOf("foo" to "bar {{ value }}"))
  }

  @Test
  fun loadFrontMatterNoTemplate() {
    val m = Yaml().loadTemplate<Map<String, Any>>("""
      ---
      title: foobar
      ---
      foo: bar {{ value }}
    """.trimIndent(), emptyMap())
    assertThat(m).isEqualTo(mapOf("foo" to "bar {{ value }}"))
  }

  @Test
  fun loadFrontMatterWhitespacesNoTemplate() {
    val m = Yaml().loadTemplate<Map<String, Any>>("""
      
      ---
      title: foobar
      ---
      
      foo: bar {{ value }}
    """.trimIndent(), emptyMap())
    assertThat(m).isEqualTo(mapOf("foo" to "bar {{ value }}"))
  }

  @Test
  fun loadFrontMatterTemplateDisabled() {
    val m = Yaml().loadTemplate<Map<String, Any>>("""
      ---
      template: false
      ---
      foo: bar {{ value }}
    """.trimIndent(), emptyMap())
    assertThat(m).isEqualTo(mapOf("foo" to "bar {{ value }}"))
  }

  @Test
  fun loadFrontMatterTemplateEnabled() {
    val m = Yaml().loadTemplate<Map<String, Any>>("""
      ---
      template: true
      ---
      foo: bar {{ value }}
    """.trimIndent(), mapOf("value" to 99))
    assertThat(m).isEqualTo(mapOf("foo" to "bar 99"))
  }

  @Test
  fun loadFrontMatterTemplateEnabledInvalidVariable() {
    assertThatThrownBy {
      Yaml().loadTemplate<Map<String, Any>>("""
        ---
        template: true
        ---
        foo: bar {{ notfound }}
      """.trimIndent(), mapOf("value" to 99))
    }.isInstanceOf(RootAttributeNotFoundException::class.java)
  }
}
