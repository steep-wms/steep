package db

import ConfigConstants
import assertThatThrownBy
import coVerify
import helper.DefaultOutputCollector
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.plugins.InitializerPlugin
import model.plugins.OutputAdapterPlugin
import model.plugins.ProcessChainAdapterPlugin
import model.plugins.ProgressEstimatorPlugin
import model.plugins.RuntimePlugin
import model.plugins.call
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import javax.script.ScriptException

/**
 * Tests for [PluginRegistryFactory] and [PluginRegistry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class PluginRegistryTest {
  @AfterEach
  fun tearDown(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      // initialize the PluginRegistryFactory with an empty list of plugins
      // after each test, so we don't spill into other tests (from this class
      // as well as from other test classes!)
      val config = json {
        obj(
            ConfigConstants.PLUGINS to JsonArray()
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      ctx.completeNow()
    }
  }

  /**
   * Test if a simple initializer can be compiled and executed
   */
  @Test
  fun compileDummyInitializer(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyInitializer.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val initializers = pr.getInitializers()
      ctx.coVerify {
        assertThat(initializers).hasSize(1)
        val initializer = initializers[0]
        var called = 0
        vertx.eventBus().consumer<Unit>("DUMMY_INITIALIZER") { msg ->
          called++
          msg.reply(null)
        }
        initializer.call(vertx)
        assertThat(called).isEqualTo(1)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if [PluginRegistry.getInitializers] works correctly
   */
  @Test
  fun getInitializers() {
    val init1 = InitializerPlugin("a", "file.kts")
    val init2 = InitializerPlugin("b", "file2.kts")
    val init3 = InitializerPlugin("c", "file3.kts")
    val expected = listOf(init1, init2, init3)
    val pr = PluginRegistry(expected)
    assertThat(pr.getInitializers()).isEqualTo(expected)
    assertThat(pr.getInitializers()).isNotSameAs(expected)
  }

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
   * Test if a simple process chain adapter can be compiled and executed
   */
  @Test
  fun compileDummyProcessChainAdapter(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyProcessChainAdapter.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val adapters = pr.getProcessChainAdapters()
      ctx.coVerify {
        assertThat(adapters).hasSize(1)
        val adapter = adapters[0]
        val pcs = listOf(ProcessChain())
        val newPcs = adapter.call(pcs, vertx)
        assertThat(newPcs).isNotSameAs(pcs)
        assertThat(newPcs).hasSize(2)
        assertThat(newPcs[0]).isSameAs(pcs[0])
        assertThat(newPcs[1]).isNotSameAs(pcs[0])
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if [PluginRegistry.getProcessChainAdapters] works correctly
   */
  @Test
  fun getProcessChainAdapters() {
    val adapter1 = ProcessChainAdapterPlugin("a", "file.kts")
    val adapter2 = ProcessChainAdapterPlugin("b", "file2.kts")
    val adapter3 = ProcessChainAdapterPlugin("c", "file3.kts")
    val expected = listOf(adapter1, adapter2, adapter3)
    val pr = PluginRegistry(expected)
    assertThat(pr.getProcessChainAdapters()).isEqualTo(expected)
    assertThat(pr.getProcessChainAdapters()).isNotSameAs(expected)
  }

  /**
   * Test if a simple progress estimator can be compiled and executed
   */
  @Test
  fun compileDummyProgressEstimator(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyProgressEstimator.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val estimator = pr.findProgressEstimator("dummy")
      ctx.coVerify {
        assertThat(estimator).isNotNull
        val e = Executable(
            path = "path",
            arguments = emptyList()
        )
        assertThat(estimator!!.compiledFunction.call(e, listOf("0"), vertx)).isEqualTo(0.0)
        assertThat(estimator.compiledFunction.call(e, listOf("10"), vertx)).isEqualTo(0.1)
        assertThat(estimator.compiledFunction.call(e, listOf("100"), vertx)).isEqualTo(1.0)
        assertThat(estimator.compiledFunction.call(e, listOf("aa"), vertx)).isEqualTo(null)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a simple progress estimator (using the deprecated `supportedServiceId'
   * property) can be compiled and executed
   */
  @Test
  fun compileDummyProgressEstimatorDeprecated(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyProgressEstimatorDeprecated.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val estimator = pr.findProgressEstimator("dummy")
      ctx.coVerify {
        assertThat(estimator).isNotNull
        val e = Executable(
            path = "path",
            arguments = emptyList()
        )
        assertThat(estimator!!.compiledFunction.call(e, listOf("0"), vertx)).isEqualTo(0.0)
        assertThat(estimator.compiledFunction.call(e, listOf("10"), vertx)).isEqualTo(0.1)
        assertThat(estimator.compiledFunction.call(e, listOf("100"), vertx)).isEqualTo(1.0)
        assertThat(estimator.compiledFunction.call(e, listOf("aa"), vertx)).isEqualTo(null)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if [PluginRegistry.findProgressEstimator] works correctly
   */
  @Test
  fun findProgressEstimator() {
    val estimator1 = ProgressEstimatorPlugin("a", "file.kts",
        supportedServiceIds = listOf("copy"))
    val estimator2 = ProgressEstimatorPlugin("b", "file2.kts",
        supportedServiceIds = listOf("sleep"))
    val estimator3 = ProgressEstimatorPlugin("c", "file3.kts",
        supportedServiceIds = listOf("hello-world"))
    val estimator4 = ProgressEstimatorPlugin("d", "file4.kts",
        supportedServiceIds = listOf("duplicate1", "duplicate2"))
    val pr = PluginRegistry(listOf(estimator1, estimator2, estimator3,
        estimator4))
    assertThat(pr.findProgressEstimator("copy")).isSameAs(estimator1)
    assertThat(pr.findProgressEstimator("wrongEstimator")).isNull()
    assertThat(pr.findProgressEstimator("duplicate1")).isSameAs(estimator4)
    assertThat(pr.findProgressEstimator("duplicate2")).isSameAs(estimator4)
  }

  /**
   * Test if a simple runtime can be compiled and executed
   */
  @Test
  fun compileDummyRuntime(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch(vertx.dispatcher()) {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyRuntime.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val runtime = pr.findRuntime("dummy")
      val outputCollector = DefaultOutputCollector()
      ctx.coVerify {
        assertThat(runtime).isNotNull
        runtime!!.compiledFunction.call(Executable(
            path = "path",
            arguments = emptyList()
        ), outputCollector, vertx)
        assertThat(outputCollector.output()).isEqualTo("DUMMY")
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if [PluginRegistry.findRuntime] works correctly
   */
  @Test
  fun findRuntime() {
    val adapter1 = RuntimePlugin("a", "file.kts", "ssh")
    val adapter2 = RuntimePlugin("b", "file2.kts", "ssh")
    val adapter3 = RuntimePlugin("c", "file3.kts", "http")
    val pr = PluginRegistry(listOf(adapter1, adapter2, adapter3))
    assertThat(pr.findRuntime("ssh")).isSameAs(adapter2)
    assertThat(pr.findRuntime("wrongRuntime")).isNull()
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
        }.isInstanceOf(IllegalArgumentException::class.java)
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
