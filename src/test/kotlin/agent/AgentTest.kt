package agent

import assertThatThrownBy
import coVerify
import db.PluginRegistry
import db.PluginRegistryFactory
import helper.OutputCollector
import helper.Shell.ExecutionException
import helper.UniqueID
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import model.plugins.RuntimePlugin
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import model.retry.RetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import runtime.OtherRuntime
import java.io.File
import java.nio.file.Path

/**
 * Tests for all [Agent] implementations
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
abstract class AgentTest {
  abstract fun createAgent(vertx: Vertx): Agent

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Executes a process chain that copies a file from a temporary directory to
   * another one
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  open fun execute(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val tempDir1 = File(tempDir.toRealPath().toFile(), "src")
    val tempDir2 = File(tempDir.toRealPath().toFile(), "dst")
    tempDir1.mkdirs()

    // create test file
    val inputFile = File(tempDir1, "test.txt")
    inputFile.writeText("Hello world")

    // create process chain that copies the test file from tempDir1 to tempDir2
    val outputArg = Argument(variable = ArgumentVariable(UniqueID.next(), tempDir2.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "cp", serviceId = "cp", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), inputFile.toString()),
                type = Argument.Type.INPUT),
            outputArg
        ))
    ))

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // execute process chain
        val results = agent.execute(processChain)

        // check results
        val outputFile = File(tempDir2, inputFile.name)
        assertThat(outputFile)
            .exists()
            .hasSameTextualContentAs(inputFile)
        assertThat(results)
            .hasSize(1)
            .contains(entry(outputArg.variable.id, listOf(outputFile.path)))
      }

      ctx.completeNow()
    }
  }

  /**
   * Executes a process chain that creates a directory with a new file. Tests
   * if outputs can be read recursively.
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  open fun recursive(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val tempDir1 = File(tempDir.toRealPath().toFile(), "src")
    val tempDir2 = File(tempDir.toRealPath().toFile(), "dst")
    val newDir = File(tempDir2, "newDir")
    val newFile = File(newDir, "newFile.txt")
    tempDir1.mkdirs()

    // create test file
    val inputFile = File(tempDir1, "test.txt")
    inputFile.writeText("Hello world")

    // create process chain that creates a new directory and then copies the
    // test file from tempDir1 to tempDir2/newDir
    val outputArg = Argument(variable = ArgumentVariable(UniqueID.next(), tempDir2.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val outputNewDirArg = Argument(variable = ArgumentVariable(UniqueID.next(), newDir.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val outputNewFileArg = Argument(variable = ArgumentVariable(UniqueID.next(), newFile.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_STRING)
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "mkdir", serviceId = "mkdir", arguments = listOf(
            Argument(label = "-p", variable = ArgumentVariable(UniqueID.next(), "true"),
                type = Argument.Type.INPUT, dataType = Argument.DATA_TYPE_BOOLEAN),
            outputNewDirArg
        )),
        Executable(path = "touch", serviceId = "touch", arguments = listOf(outputNewFileArg)),
        Executable(path = "cp", serviceId = "cp", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), inputFile.toString()),
                type = Argument.Type.INPUT),
            outputArg
        ))
    ))

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // execute process chain
        val results = agent.execute(processChain)

        // check results
        val outputFile = File(tempDir2, inputFile.name)
        assertThat(outputFile)
            .exists()
            .hasSameTextualContentAs(inputFile)
        assertThat(newFile)
            .exists()
            .hasContent("")
        assertThat(results)
            .hasSize(3)
            .contains(entry(outputArg.variable.id, listOf(outputFile.path, newFile.path)))
            .contains(entry(outputNewDirArg.variable.id, listOf(newFile.path)))
            .contains(entry(outputNewFileArg.variable.id, listOf(newFile.path)))
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if the agent can call a custom runtime
   */
  @Test
  open fun customRuntime(vertx: Vertx, ctx: VertxTestContext) {
    val customRuntimeName = "foobar"

    val customRuntime = spyk(object {
      @Suppress("UNUSED_PARAMETER")
      fun execute(executable: Executable, outputCollector: OutputCollector,
          vertx: Vertx) {
        outputCollector.collect("hello")
        outputCollector.collect("world")
        outputCollector.collect(executable.path)
      }
    })

    val pluginRegistry = mockk<PluginRegistry>()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry
    every { pluginRegistry.findRuntime(customRuntimeName) } returns RuntimePlugin(
        name = customRuntimeName,
        scriptFile = "",
        supportedRuntime = customRuntimeName,
        compiledFunction = customRuntime::execute
    )
    every { pluginRegistry.findProgressEstimator(any()) } returns null

    val exec = Executable(path = "ls", serviceId = "ls",
        arguments = emptyList(), runtime = customRuntimeName)
    val processChain = ProcessChain(executables = listOf(exec))

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        agent.execute(processChain)
        verify(exactly = 1) {
          customRuntime.execute(exec, any(), any())
        }
      }
      ctx.completeNow()
    }
  }

  protected fun doCustomRuntimeThrows(vertx: Vertx, ctx: VertxTestContext,
      assert: (expected: Throwable, actual: Throwable) -> Boolean) {
    val customRuntimeName = "foobar"
    val ex = ExecutionException(message = "foobar", lastOutput = "", exitCode = 1)

    val customRuntime = spyk(object {
      @Suppress("UNUSED_PARAMETER")
      fun execute(executable: Executable, outputCollector: OutputCollector,
          vertx: Vertx) {
        throw ex
      }
    })

    val pluginRegistry = mockk<PluginRegistry>()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry
    every { pluginRegistry.findRuntime(customRuntimeName) } returns RuntimePlugin(
        name = customRuntimeName,
        scriptFile = "",
        supportedRuntime = customRuntimeName,
        compiledFunction = customRuntime::execute
    )
    every { pluginRegistry.findProgressEstimator(any()) } returns null

    val exec = Executable(path = "ls", serviceId = "ls",
        arguments = emptyList(), runtime = customRuntimeName)
    val processChain = ProcessChain(executables = listOf(exec))

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain) }.matches { t -> assert(ex, t) }
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the agent can call a custom runtime that throws an exception
   */
  @Test
  open fun customRuntimeThrows(vertx: Vertx, ctx: VertxTestContext) {
    doCustomRuntimeThrows(vertx, ctx) { expected, actual ->
      actual === expected
    }
  }

  /**
   * Tests if a service can be executed with a retry policy
   */
  @Test
  open fun retry(vertx: Vertx, ctx: VertxTestContext) {
    mockkConstructor(OtherRuntime::class)
    var called = false
    every { anyConstructed<OtherRuntime>().execute(any(), any() as OutputCollector) } throws
      ExecutionException("", "", 1) andThenThrows ExecutionException("", "", 2) andThenAnswer {
      called = true
    }

    val processChain = ProcessChain(executables = listOf(
        Executable(path = "ls", serviceId = "ls", arguments = emptyList(),
            retries = RetryPolicy(maxAttempts = 4))
    ))

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // execute process chain
        agent.execute(processChain)

        verify(exactly = 3) {
          anyConstructed<OtherRuntime>().execute(any(), any() as OutputCollector)
        }
        assertThat(called).isTrue()
      }

      ctx.completeNow()
    }
  }
}
