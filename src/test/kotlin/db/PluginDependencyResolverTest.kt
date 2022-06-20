package db

import model.plugins.DependentPlugin
import model.plugins.throwPluginNeedsCompile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction

/**
 * Tests for [PluginDependencyResolver]
 * @author Michel Kraemer
 */
class PluginDependencyResolverTest {
  private data class DummyPlugin(override val name: String,
      override val dependsOn: List<String>,
      override val scriptFile: String = "foobar.kt",
      override val version: String? = null,
      override val compiledFunction: KFunction<*> = throwPluginNeedsCompile<Any>()) : DependentPlugin

  /**
   * Test if plugins with no dependencies are all included in the same order
   */
  @Test
  fun noDependencies() {
    val plugins = listOf(DummyPlugin("A", emptyList()),
        DummyPlugin("B", emptyList()), DummyPlugin("C", emptyList()))
    val sortedPlugins = PluginDependencyResolver.resolve(plugins)
    assertThat(sortedPlugins).isEqualTo(plugins)
  }

  /**
   * Test that two plugins B and A are returned in the correct order if B
   * depends on A
   */
  @Test
  fun singleDependency() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", listOf("A"))
    val plugins = listOf(b, a)
    val sortedPlugins = PluginDependencyResolver.resolve(plugins)
    assertThat(sortedPlugins).isEqualTo(listOf(a, b))
  }

  /**
   * Test that four plugins are sorted correctly
   */
  @Test
  fun twoSingleDependencies() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", listOf("A"))
    val c = DummyPlugin("C", listOf("D"))
    val d = DummyPlugin("D", emptyList())
    val plugins = listOf(b, a, c, d)
    val sortedPlugins = PluginDependencyResolver.resolve(plugins)
    assertThat(sortedPlugins).isEqualTo(listOf(a, d, b, c))
  }

  /**
   * Test that four plugins are sorted correctly
   */
  @Test
  fun twoDependencies() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", emptyList())
    val c = DummyPlugin("C", listOf("B"))
    val d = DummyPlugin("D", listOf("A", "C"))
    val plugins = listOf(d, c, b, a)
    val sortedPlugins = PluginDependencyResolver.resolve(plugins)
    assertThat(sortedPlugins).isEqualTo(listOf(b, a, c, d))
  }

  /**
   * Test that a circular dependency is detected
   */
  @Test
  fun circularDependency() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", listOf("A"))
    val c = DummyPlugin("C", listOf("D"))
    val d = DummyPlugin("D", listOf("C"))
    val plugins = listOf(b, a, c, d)
    assertThatThrownBy { PluginDependencyResolver.resolve(plugins) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("plugins `C', `D'")
  }

  /**
   * Test that an invalid dependency is detected
   */
  @Test
  fun invalidDependency() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", listOf("D"))
    val plugins = listOf(b, a)
    assertThatThrownBy { PluginDependencyResolver.resolve(plugins) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("`B' depends on plugin `D'")
  }

  /**
   * Test that three plugins C, B, and A are returned in the correct order if C
   * depends on B and B depends on A
   */
  @Test
  fun dependencyChain() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", listOf("A"))
    val c = DummyPlugin("C", listOf("B"))
    val d = DummyPlugin("D", emptyList())
    val plugins = listOf(c, b, d, a)
    val sortedPlugins = PluginDependencyResolver.resolve(plugins)
    assertThat(sortedPlugins).isEqualTo(listOf(d, a, b, c))
  }

  /**
   * Test that a circular dependency is detected
   */
  @Test
  fun circularDependencyChain() {
    val a = DummyPlugin("A", emptyList())
    val b = DummyPlugin("B", listOf("D"))
    val c = DummyPlugin("C", listOf("B"))
    val d = DummyPlugin("D", listOf("C"))
    val plugins = listOf(b, a, c, d)
    assertThatThrownBy { PluginDependencyResolver.resolve(plugins) }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("plugins `B', `C', `D'")
  }
}
