package helper

import helper.DependencyResolver.DependencyCycleException
import helper.DependencyResolver.MissingDependencyException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Tests [DependencyResolver]
 * @author Michel Kraemer
 */
class DependencyResolverTest {
  private data class N(
      override val id: String,
      override val dependsOn: List<String>
  ): DependencyResolver.Node

  @Test
  fun valid() {
    val nodes = listOf(
        N("c", listOf("b")),
        N("a", emptyList()),
        N("b", listOf("a")),
    )

    val result = DependencyResolver.resolve(nodes).map { it.id }
    assertThat(result).containsExactly("a", "b", "c")
  }

  @Test
  fun cycle() {
    val nodes = listOf(
        N("c", listOf("b")),
        N("b", listOf("a")),
        N("a", listOf("c")),
    )

    assertThatThrownBy {
      DependencyResolver.resolve(nodes).map { it.id }
    }.isInstanceOf(DependencyCycleException::class.java)
        .matches { e ->
          (e as DependencyCycleException).nodes.map { it.id } == listOf(
              "c", "b", "a")
        }
  }

  @Test
  fun cycleAndOthers() {
    val nodes = listOf(
        N("d", listOf("a")),
        N("e", listOf("d")),
        N("c", listOf("b")),
        N("b", listOf("a")),
        N("a", listOf("c")),
        N("f", emptyList()),
        N("g", emptyList()),
        N("h", listOf("f", "g")),
    )

    assertThatThrownBy {
      DependencyResolver.resolve(nodes).map { it.id }
    }.isInstanceOf(DependencyCycleException::class.java)
        .matches { e ->
          (e as DependencyCycleException).nodes.map { it.id } == listOf(
              "d", "e", "c", "b", "a")
        }
  }

  @Test
  fun selfCycle() {
    val nodes = listOf(
        N("a", listOf("a")),
    )

    assertThatThrownBy {
      DependencyResolver.resolve(nodes).map { it.id }
    }.isInstanceOf(DependencyCycleException::class.java)
        .matches { e ->
          (e as DependencyCycleException).nodes.map { it.id } == listOf("a")
        }
  }

  @Test
  fun missingDependency() {
    val nodes = listOf(
        N("d", listOf("a", "b")),
        N("a", emptyList()),
    )

    assertThatThrownBy {
      DependencyResolver.resolve(nodes).map { it.id }
    }.isInstanceOf(MissingDependencyException::class.java)
        .matches { e ->
          val mde = e as MissingDependencyException
          mde.node == "d" && mde.dependsOn == "b"
        }
  }
}
