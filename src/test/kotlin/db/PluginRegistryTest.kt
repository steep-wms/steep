package db

import ConfigConstants
import assertThatThrownBy
import coVerify
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.plugins.OutputAdapterPlugin
import model.plugins.call
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import javax.script.ScriptException

/**
 * Tests for [PluginRegistryFactory] and [PluginRegistry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class PluginRegistryTest {
  /**
   * Test if a simple output adapter can be compiled and executed
   */
  @Test
  fun compileDummyOutputAdapter(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyOutputAdapter.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val adapter = pr.findOutputAdapter("dummy")
      ctx.coVerify {
        assertThat(adapter).isNotNull
        assertThat(adapter!!.call(Argument(
            variable = ArgumentVariable("id", "myValue"),
            type = Argument.Type.OUTPUT
        ), ProcessChain(), vertx)).isEqualTo(listOf("myValue1", "myValue2"))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if [PluginRegistry.findOutputAdapter] works correctly
   */
  @Test
  fun findOutputAdapter() {
    val adapter1 = OutputAdapterPlugin("a", "file.kts", "dataType")
    val adapter2 = OutputAdapterPlugin("b", "file2.kts", "dataType")
    val adapter3 = OutputAdapterPlugin("c", "file3.kts", "custom")
    val pr = PluginRegistry(listOf(adapter1, adapter2, adapter3))
    assertThat(pr.findOutputAdapter("dataType")).isSameAs(adapter2)
    assertThat(pr.findOutputAdapter("wrongDataType")).isNull()
  }

  /**
   * Make sure that an invalid plugin descriptor cannot be read
   */
  @Test
  fun invalidPluginDescriptor(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/invalidPluginDescriptor.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(InvalidTypeIdException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure that a plugin with a missing script file cannot be compiled
   */
  @Test
  fun missingScriptFile(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/missingScriptFile.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(io.vertx.core.file.FileSystemException::class.java)
            .hasCauseInstanceOf(java.nio.file.NoSuchFileException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure that an invalid plugin script cannot be compiled
   */
  @Test
  fun compileError(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/compileError.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(ScriptException::class.java)
      }
      ctx.completeNow()
    }
  }
}
