package db

import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [MetadataRegistry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class MetadataRegistryTest {
  /**
   * Test if a YAML file with references can be loaded
   */
  @Test
  fun yamlWithReferences(vertx: Vertx, ctx: VertxTestContext) {
    val registry = FileMetadataRegistry(listOf("src/test/resources/db/services_with_references.yaml"), vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val services = registry.findServices()
        assertThat(services).hasSize(2)
        val s1 = services[0]
        val s2 = services[1]
        assertThat(s1.id).isEqualTo("service1")
        assertThat(s2.id).isEqualTo("service2")
        assertThat(s1.path).isEqualTo("service.sh")
        assertThat(s2.path).isEqualTo(s1.path)
        assertThat(s2.runtime).isEqualTo(s1.runtime)
        assertThat(s2.parameters).isEmpty()
      }
      ctx.completeNow()
    }
  }
}
