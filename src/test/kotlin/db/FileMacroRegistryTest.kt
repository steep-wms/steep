package db

import assertThatThrownBy
import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [MacroRegistry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class FileMacroRegistryTest {
  /**
   * Test if a definition of a single macro can be loaded
   */
  @Test
  fun singleMacro(vertx: Vertx, ctx: VertxTestContext) {
    val registry = FileMacroRegistry(listOf("src/test/resources/db/singleMacro.yaml"), vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val macros = registry.findMacros()
        assertThat(macros).hasSize(1).containsKey("delayed_docker_hello_world")
        val m1 = macros["delayed_docker_hello_world"]!!
        assertThat(m1.id).isEqualTo("delayed_docker_hello_world")
        assertThat(m1.parameters).hasSize(1)
        assertThat(m1.actions).hasSize(2)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if a duplicate macro IDs are detected
   */
  @Test
  fun duplicateIds(vertx: Vertx, ctx: VertxTestContext) {
    val registry = FileMacroRegistry(listOf("src/test/resources/db/macrosWithDuplicateIDs.yaml"), vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy {
          registry.findMacros()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid macro configuration. See log for details.")
      }
      ctx.completeNow()
    }
  }
}
