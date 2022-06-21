package db

import ConfigConstants
import assertThatThrownBy
import coVerify
import com.github.zafarkhaja.semver.ParseException
import helper.DefaultOutputCollector
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
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
import model.workflow.ExecuteAction
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Tests for [PluginRegistryFactory] and [PluginRegistry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class PluginRegistryTest {
  @AfterEach
  fun tearDown(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
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
    CoroutineScope(vertx.dispatcher()).launch {
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
   * Test if [PluginRegistry.getInitializers] works correctly if the plugins
   * have dependencies
   */
  @Test
  fun getInitializersWithDependencies() {
    val init1 = InitializerPlugin("a", "file.kts", dependsOn = listOf("c"))
    val init2 = InitializerPlugin("b", "file2.kts")
    val init3 = InitializerPlugin("c", "file3.kts")
    val pr = PluginRegistry(listOf(init1, init2, init3))
    assertThat(pr.getInitializers()).isEqualTo(listOf(init2, init3, init1))
  }

  private fun doCompileDummyOutputAdapter(vertx: Vertx, ctx: VertxTestContext, name: String) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/$name.yaml"
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
   * Test if a simple output adapter can be compiled and executed
   */
  @Test
  fun compileDummyOutputAdapter(vertx: Vertx, ctx: VertxTestContext) {
    doCompileDummyOutputAdapter(vertx, ctx, "dummyOutputAdapter")
  }

  /**
   * Test if a simple output adapter can be compiled and executed even if it
   * has less parameters than specified by the API
   */
  @Test
  fun compileDummyOutputAdapterWithoutUnusedParameters(vertx: Vertx,
      ctx: VertxTestContext) {
    doCompileDummyOutputAdapter(vertx, ctx,
        "dummyOutputAdapterWithoutUnusedParameters")
  }

  /**
   * Test if [PluginRegistry.findOutputAdapter] works correctly
   */
  @Test
  fun findOutputAdapter() {
    val adapter1 = OutputAdapterPlugin("a", "file.kts", supportedDataType = "dataType")
    val adapter2 = OutputAdapterPlugin("b", "file2.kts", supportedDataType = "dataType")
    val adapter3 = OutputAdapterPlugin("c", "file3.kts", supportedDataType = "custom")
    val pr = PluginRegistry(listOf(adapter1, adapter2, adapter3))
    assertThat(pr.findOutputAdapter("dataType")).isSameAs(adapter2)
    assertThat(pr.findOutputAdapter("wrongDataType")).isNull()
  }

  private fun doCompileDummyProcessChainAdapter(vertx: Vertx,
      ctx: VertxTestContext, name: String) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/$name.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val expectedId = UniqueID.next()
      val pr = PluginRegistryFactory.create()
      val adapters = pr.getProcessChainAdapters()
      ctx.coVerify {
        assertThat(adapters).hasSize(1)
        val adapter = adapters[0]
        val pcs = listOf(ProcessChain())
        val newPcs = adapter.call(pcs, Workflow(name = expectedId), vertx)
        assertThat(newPcs).isNotSameAs(pcs)
        assertThat(newPcs).hasSize(2)
        assertThat(newPcs[0]).isSameAs(pcs[0])
        assertThat(newPcs[1]).isNotSameAs(pcs[0])
        assertThat(newPcs[1].id).isEqualTo(expectedId)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a simple process chain adapter can be compiled and executed
   */
  @Test
  fun compileDummyProcessChainAdapter(vertx: Vertx, ctx: VertxTestContext) {
    doCompileDummyProcessChainAdapter(vertx, ctx, "dummyProcessChainAdapter")
  }

  /**
   * Test if a simple process chain adapter can be compiled and executed even
   * if the parameter order is different to the API
   */
  @Test
  fun compileDummyProcessChainAdapterWithOtherParameterOrder(vertx: Vertx,
      ctx: VertxTestContext) {
    doCompileDummyProcessChainAdapter(vertx, ctx,
        "dummyProcessChainAdapterWithOtherParameterOrder")
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
   * Test if [PluginRegistry.getProcessChainAdapters] returns plugins with
   * dependencies in the correct order
   */
  @Test
  fun getProcessChainAdaptersWithDependencies() {
    val adapter1 = ProcessChainAdapterPlugin("a", "file.kts", dependsOn = listOf("c"))
    val adapter2 = ProcessChainAdapterPlugin("b", "file2.kts")
    val adapter3 = ProcessChainAdapterPlugin("c", "file3.kts")
    val pr = PluginRegistry(listOf(adapter1, adapter2, adapter3))
    assertThat(pr.getProcessChainAdapters()).isEqualTo(listOf(adapter2, adapter3, adapter1))
  }

  @Test
  fun compileDummyProcessChainConsistencyChecker(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/dummyProcessChainConsistencyChecker.yaml"
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      val pr = PluginRegistryFactory.create()
      val checkers = pr.getProcessChainConsistencyCheckers()
      ctx.coVerify {
        assertThat(checkers).hasSize(1)
        val checker = checkers[0]

        val pc1 = emptyList<Executable>()
        val a = ExecuteAction(id = "foobar", service = "cp")
        assertThat(checker.call(pc1, a, Workflow(), vertx)).isTrue

        val pc2 = listOf(Executable(id = a.id, path = "cp", serviceId = a.service,
            arguments = emptyList()))
        assertThat(checker.call(pc2, a, Workflow(), vertx)).isFalse
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a simple progress estimator can be compiled and executed
   */
  @Test
  fun compileDummyProgressEstimator(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
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
            serviceId = "foobar",
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
    CoroutineScope(vertx.dispatcher()).launch {
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
            serviceId = "foobar",
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
    val adapter1 = RuntimePlugin("a", "file.kts", supportedRuntime = "ssh")
    val adapter2 = RuntimePlugin("b", "file2.kts", supportedRuntime = "ssh")
    val adapter3 = RuntimePlugin("c", "file3.kts", supportedRuntime = "http")
    val pr = PluginRegistry(listOf(adapter1, adapter2, adapter3))
    assertThat(pr.findRuntime("ssh")).isSameAs(adapter2)
    assertThat(pr.findRuntime("wrongRuntime")).isNull()
  }

  /**
   * Make sure that an invalid plugin descriptor cannot be read
   */
  @Test
  fun invalidPluginDescriptor(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
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
   * Make sure that a plugin with an invalid function signature cannot be compiled
   */
  @Test
  fun invalidPluginSignature(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/invalidPluginSignature.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContainingAll("executables", "model.processchain.Executable")
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure a plugin with an invalid semantic version cannot be loaded
   */
  @Test
  fun invalidPluginVersion(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/invalidPluginVersion.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContainingAll("Version", "dummyOutputAdapter",
                "must follow the semantic versioning")
            .hasCauseInstanceOf(ParseException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure a plugin with an empty version string cannot be loaded
   */
  @Test
  fun emptyPluginVersion(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/emptyPluginVersion.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContainingAll("Version", "dummyOutputAdapter", "must not be empty")
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure that a plugin with a missing script file cannot be compiled
   */
  @Test
  fun missingScriptFile(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/missingScriptFile.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(io.vertx.core.file.FileSystemException::class.java)
            .hasRootCauseInstanceOf(java.nio.file.NoSuchFileException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure that an invalid plugin script cannot be compiled
   */
  @Test
  fun compileError(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/compileError.yaml"
        )
      }
      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(RuntimeException::class.java)
          .hasMessageContaining("Unresolved reference")
      }
      ctx.completeNow()
    }
  }

  /**
   * Make sure the cache directory is not created if caching is disabled
   */
  @Test
  fun noCache(@TempDir tempDir: File, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val cacheDir = File(tempDir, "cache")
      val config = json {
        obj(
          ConfigConstants.PLUGINS to "src/**/db/dummyInitializer.yaml",
          ConfigConstants.CACHE_PLUGINS_ENABLED to false,
          ConfigConstants.CACHE_PLUGINS_PATH to cacheDir.absolutePath,
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      assertThat(cacheDir).doesNotExist()

      ctx.completeNow()
    }
  }

  /**
   * Test if the plugin cache can be used
   */
  @Test
  fun useCache(@TempDir tempDir: File, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val cacheDir = File(tempDir, "cache")
      val config = json {
        obj(
          ConfigConstants.PLUGINS to "src/**/db/dummyInitializer.yaml",
          ConfigConstants.CACHE_PLUGINS_ENABLED to true,
          ConfigConstants.CACHE_PLUGINS_PATH to cacheDir.absolutePath,
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      assertThat(cacheDir).exists()
      assertThat(cacheDir.listFiles()).hasSize(1)

      // compile again
      PluginRegistryFactory.initialize(vertx, config)

      // there should still be only one file
      assertThat(cacheDir.listFiles()).hasSize(1)

      ctx.completeNow()
    }
  }

  /**
   * Test if the plugin cache can be updated
   */
  @Test
  @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
  fun updateCache(@TempDir tempDir: File, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val script1 = PluginRegistryTest::class.java.getResource("dummyInitializer.kts").readBytes()
      val script2 = PluginRegistryTest::class.java.getResource("dummyRuntime.kts").readBytes()

      val scriptFile = File(tempDir, "script.kt")
      scriptFile.writeBytes(script1)

      val yamlFile = File(tempDir, "script.yaml")
      yamlFile.writeText("""
        - name: dummyInitializer
          type: initializer
          scriptFile: "${tempDir.absolutePath}/script.kt"
      """.trimIndent())

      val cacheDir = File(tempDir, "cache")
      val config = json {
        obj(
          ConfigConstants.PLUGINS to yamlFile.absolutePath,
          ConfigConstants.CACHE_PLUGINS_ENABLED to true,
          ConfigConstants.CACHE_PLUGINS_PATH to cacheDir.absolutePath,
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      assertThat(cacheDir).exists()
      assertThat(cacheDir.listFiles()).hasSize(1)

      // compile again with modified script
      scriptFile.writeBytes(script2)
      yamlFile.writeText("""
        - name: dummyRuntime
          type: runtime
          supportedRuntime: dummy
          scriptFile: "${tempDir.absolutePath}/script.kt"
      """.trimIndent())
      PluginRegistryFactory.initialize(vertx, config)

      // there should now be another cached script
      assertThat(cacheDir).exists()
      assertThat(cacheDir.listFiles()).hasSize(2)

      ctx.completeNow()
    }
  }

  /**
   * Test if an invalid cache entry is deleted and the script is recompiled
   */
  @Test
  @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
  fun invalidCache(@TempDir tempDir: File, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      // compile normally
      val cacheDir = File(tempDir, "cache")
      val config = json {
        obj(
          ConfigConstants.PLUGINS to "src/**/db/dummyInitializer.yaml",
          ConfigConstants.CACHE_PLUGINS_ENABLED to true,
          ConfigConstants.CACHE_PLUGINS_PATH to cacheDir.absolutePath,
        )
      }
      PluginRegistryFactory.initialize(vertx, config)

      assertThat(cacheDir).exists()
      assertThat(cacheDir.listFiles()).hasSize(1)

      // make cache corrupt
      cacheDir.listFiles().first().writeText("")
      assertThat(cacheDir.listFiles().first()).isEmpty

      // compile again
      PluginRegistryFactory.initialize(vertx, config)

      // the cache entry should have been recreated
      assertThat(cacheDir.listFiles()).hasSize(1)
      assertThat(cacheDir.listFiles().first()).isNotEmpty

      ctx.completeNow()
    }
  }

  /**
   * Make sure that an invalid plugin script is not cached
   */
  @Test
  fun compileErrorNoCache(@TempDir tempDir: File, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val cacheDir = File(tempDir, "cache")
      val config = json {
        obj(
          ConfigConstants.PLUGINS to "src/**/db/compileError.yaml",
          ConfigConstants.CACHE_PLUGINS_ENABLED to true,
          ConfigConstants.CACHE_PLUGINS_PATH to cacheDir.absolutePath,
        )
      }

      ctx.coVerify {
        assertThatThrownBy {
          PluginRegistryFactory.initialize(vertx, config)
        }.isInstanceOf(RuntimeException::class.java)
          .hasMessageContaining("Unresolved reference")
      }

      // cache directory should exist but be empty
      assertThat(cacheDir).exists()
      assertThat(cacheDir).isEmptyDirectory

      ctx.completeNow()
    }
  }

  /**
   * Tests if we can read an empty plugin configuration file. Previously,
   * this failed with a [NullPointerException]
   */
  @Test
  fun emptyPluginConfig(vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val config = json {
        obj(
            ConfigConstants.PLUGINS to "src/**/db/emptyPluginConfig.yaml"
        )
      }

      // should not fail!
      PluginRegistryFactory.initialize(vertx, config)

      ctx.completeNow()
    }
  }
}
