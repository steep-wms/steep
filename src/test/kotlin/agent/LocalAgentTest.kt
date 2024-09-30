package agent

import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import ConfigConstants
import assertThatThrownBy
import coVerify
import db.PluginRegistry
import db.PluginRegistryFactory
import helper.FileSystemUtils
import helper.OutputCollector
import helper.Shell
import helper.UniqueID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import model.metadata.Service
import model.plugins.InputAdapterPlugin
import model.plugins.ProgressEstimatorPlugin
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import model.retry.RetryPolicy
import model.timeout.TimeoutPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.OtherRuntime
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Tests for [LocalAgent]
 * @author Michel Kraemer
 */
class LocalAgentTest : AgentTest() {
  private val executorService = Executors.newCachedThreadPool()
  private val localAgentDispatcher = executorService.asCoroutineDispatcher()

  override fun createAgent(vertx: Vertx): Agent = LocalAgent(vertx, localAgentDispatcher)

  /**
   * Test if a process chain execution can be cancelled
   */
  @Test
  fun cancel(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "sleep", serviceId = "sleep", arguments = listOf(
            // sleep for a long time so we run into a timeout if cancelling does not work
            Argument(variable = ArgumentVariable(UniqueID.next(), "2000"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    vertx.setTimer(200) {
      agent.cancel()
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain, 1) }
            .isInstanceOf(CancellationException::class.java)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a process chain execution can be cancelled by sending a message
   * over the event bus
   */
  @Test
  fun cancelByMessage(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "sleep", serviceId = "sleep", arguments = listOf(
            // sleep for a long time so we run into a timeout if cancelling does not work
            Argument(variable = ArgumentVariable(UniqueID.next(), "2000"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    vertx.setTimer(200) {
      vertx.eventBus().send(LOCAL_AGENT_ADDRESS_PREFIX + processChain.id, jsonObjectOf(
          "action" to "cancel"
      ))
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain, 1) }
            .isInstanceOf(CancellationException::class.java)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if we can cancel a process chain even if we are currently waiting
   * for a retry
   */
  @Test
  fun cancelRetryDelay(vertx: Vertx, ctx: VertxTestContext) {
    mockkConstructor(OtherRuntime::class)
    every { anyConstructed<OtherRuntime>().execute(any(), any() as OutputCollector) } throws
        Shell.ExecutionException("", "", 1)

    val processChain = ProcessChain(executables = listOf(
        Executable(path = "ls", serviceId = "ls", arguments = emptyList(),
            retries = RetryPolicy(
                maxAttempts = 4,
                // use very long delay that would definitely fail the test
                delay = 600000
            )
        )
    ))

    val agent = createAgent(vertx)

    // cancel process chain after 200ms (while [agent.execute] waits for the next attempt)
    vertx.setTimer(200) {
      vertx.eventBus().send(LOCAL_AGENT_ADDRESS_PREFIX + processChain.id, jsonObjectOf(
          "action" to "cancel"
      ))
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // execute process chain
        assertThatThrownBy { agent.execute(processChain, 1) }
            .isInstanceOf(CancellationException::class.java)

        // execution should have been tried exactly once
        verify(exactly = 1) {
          anyConstructed<OtherRuntime>().execute(any(), any() as OutputCollector)
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if we can get the current estimated progress
   */
  @Test
  fun getProgress(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "sleep", serviceId = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        )),
        Executable(path = "sleep", serviceId = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        )),
        Executable(path = "sleep", serviceId = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    vertx.setTimer(200) {
      val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
      CoroutineScope(vertx.dispatcher()).launch {
        ctx.coVerify {
          val msg = vertx.eventBus().request<Double?>(address, jsonObjectOf(
              "action" to "getProgress"
          )).coAwait<Message<Double?>>()
          assertThat(msg.body()).isNull()
        }
      }
    }

    vertx.setTimer(1400) {
      val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
      CoroutineScope(vertx.dispatcher()).launch {
        ctx.coVerify {
          val msg = vertx.eventBus().request<Double?>(address, jsonObjectOf(
              "action" to "getProgress"
          )).coAwait<Message<Double?>>()
          assertThat(msg.body()).isGreaterThan(0.0)
        }
      }
    }

    CoroutineScope(vertx.dispatcher()).launch {
      agent.execute(processChain, 1)
      ctx.completeNow()
    }
  }

  /**
   * Test if a progress estimator can be used
   */
  @Test
  fun progressEstimator(vertx: Vertx, ctx: VertxTestContext) {
    // mock progress estimator
    val customProgressEstimatorName = "foobar"

    val customProgressEstimator = spyk(object {
      @Suppress("UNUSED_PARAMETER")
      fun execute(executable: Executable, recentLines: List<String>,
          vertx: Vertx): Double = recentLines.last().toDouble() / 5
    })

    val pluginRegistry = mockk<PluginRegistry>()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry
    every {
      pluginRegistry.findProgressEstimator(customProgressEstimatorName)
    } returns ProgressEstimatorPlugin(
        name = customProgressEstimatorName,
        scriptFile = "",
        supportedServiceIds = listOf(customProgressEstimatorName),
        compiledFunction = customProgressEstimator::execute
    )

    val exec = Executable(path = "ls", arguments = emptyList(),
        serviceId = customProgressEstimatorName)
    val processChain = ProcessChain(executables = listOf(exec))

    // mock runtime
    mockkConstructor(OtherRuntime::class)
    every { anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector) } answers {
      val collector = arg<OutputCollector>(1)
      for (i in 0 until 5) {
        collector.collect("$i")
        Thread.sleep(200)

        // validate progress
        runBlocking(vertx.dispatcher()) {
          val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
          val msg = vertx.eventBus().request<Double?>(address, jsonObjectOf(
              "action" to "getProgress"
          )).coAwait()
          assertThat(msg.body()).isEqualTo(i / 5.0)
        }
      }
    }

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        agent.execute(processChain, 1)
        verify(exactly = 5) {
          customProgressEstimator.execute(exec, any(), vertx)
        }
        verify(exactly = 1) {
          anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector)
        }
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the progress is calculated correctly if an executable is retried
   */
  @Test
  fun progressRetry(vertx: Vertx, ctx: VertxTestContext) {
    // mock progress estimator
    val customProgressEstimatorName = "foobar"

    val customProgressEstimator = spyk(object {
      @Suppress("UNUSED_PARAMETER")
      fun execute(executable: Executable, recentLines: List<String>,
          vertx: Vertx): Double = recentLines.last().toDouble() / 5
    })

    val pluginRegistry = mockk<PluginRegistry>()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry
    every {
      pluginRegistry.findProgressEstimator(customProgressEstimatorName)
    } returns ProgressEstimatorPlugin(
        name = customProgressEstimatorName,
        scriptFile = "",
        supportedServiceIds = listOf(customProgressEstimatorName),
        compiledFunction = customProgressEstimator::execute
    )

    val exec = Executable(path = "ls", arguments = emptyList(),
        serviceId = customProgressEstimatorName, retries = RetryPolicy(2))
    val processChain = ProcessChain(executables = listOf(exec))

    // mock runtime
    mockkConstructor(OtherRuntime::class)
    var shouldFail = true
    every { anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector) } answers {
      val collector = arg<OutputCollector>(1)
      for (i in 0 until 5) {
        collector.collect("$i")
        Thread.sleep(200)

        // validate progress
        runBlocking(vertx.dispatcher()) {
          val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
          val msg = vertx.eventBus().request<Double?>(address, jsonObjectOf(
              "action" to "getProgress"
          )).coAwait()
          assertThat(msg.body()).isEqualTo(i / 5.0)
        }

        if (shouldFail && i == 3) {
          // Pretend we failed. We we are retried, we should still be able to
          // get the same estimated progress.
          shouldFail = false
          throw Shell.ExecutionException("", "", 0)
        }
      }
    }

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        agent.execute(processChain, 1)
        verify(exactly = 9) {
          customProgressEstimator.execute(exec, any(), vertx)
        }
        verify(exactly = 2) {
          anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector)
        }
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if an invalid message can be sent to the local agent
   */
  @Test
  fun invalidMessage(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "sleep", serviceId = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    var messageSent = false
    vertx.setTimer(200) {
      val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
      CoroutineScope(vertx.dispatcher()).launch {
        ctx.coVerify {
          assertThatThrownBy {
            vertx.eventBus().request<Double?>(address, jsonObjectOf(
                "action" to "INVALID_ACTION"
            )).coAwait()
          }.hasMessage("Invalid action")
          messageSent = true
        }
      }
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        agent.execute(processChain, 1)
        assertThat(messageSent).isTrue
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if a service with [Service.RUNTIME_DOCKER] can be executed
   */
  @Test
  fun docker(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString()
    )

    val processChain = ProcessChain(executables = listOf(
        Executable(path = "alpine", serviceId = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
                type = Argument.Type.INPUT),
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        ), runtime = Service.RUNTIME_DOCKER)
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher, config)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val start = System.currentTimeMillis()
        agent.execute(processChain, 1)
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(1000)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if a service with [Service.RUNTIME_DOCKER] can fail
   */
  @Test
  fun dockerFail(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString()
    )

    val processChain = ProcessChain(executables = listOf(
        Executable(path = "alpine", serviceId = "false", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "false"),
                type = Argument.Type.INPUT)
        ), runtime = Service.RUNTIME_DOCKER)
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher, config)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain, 1) }
            .isInstanceOf(Shell.ExecutionException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if a process chain with a Docker container can be cancelled
   */
  @Test
  fun dockerCancel(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString()
    )

    val processChain = ProcessChain(executables = listOf(
        Executable(path = "alpine", serviceId = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
                type = Argument.Type.INPUT),
            // sleep for a long time so we run into a timeout if cancelling does not work
            Argument(variable = ArgumentVariable(UniqueID.next(), "2000"),
                type = Argument.Type.INPUT)
        ), runtime = Service.RUNTIME_DOCKER)
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher, config)

    vertx.setTimer(200) {
      agent.cancel()
    }

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain, 1) }
            .isInstanceOf(CancellationException::class.java)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if output directories are traversed.
   */
  @Test
  fun traverseOutputDirectory(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val outputFile = File(tempDir.toRealPath().toFile(), "file")
    val outputDir = File(tempDir.toRealPath().toFile(), "dir")
    val processChain = ProcessChain(executables = listOf(
      Executable(path = "echo", serviceId = "echo", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), outputFile.absolutePath),
          type = Argument.Type.OUTPUT,
          dataType = "file"),
        Argument(variable = ArgumentVariable(UniqueID.next(), outputDir.absolutePath),
          type = Argument.Type.OUTPUT,
          dataType = Argument.DATA_TYPE_DIRECTORY)
      ))
    ))

    // Since the filesystem will not be available just return an empty list.
    mockkObject(FileSystemUtils)
    coEvery {
      FileSystemUtils.readRecursive(any(), any())
    } answers {
      listOf()
    }

    val config = JsonObject()
    CoroutineScope(vertx.dispatcher()).launch {
      LocalAgent(vertx, localAgentDispatcher, config).execute(processChain, 1)
      // only the directory (argument 2) should have been traversed but not
      // the file (argument 1)
      coVerify(exactly = 0) {
        FileSystemUtils.readRecursive(outputFile.absolutePath, any())
      }
      coVerify(exactly = 1) {
        FileSystemUtils.readRecursive(outputDir.absolutePath, any())
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a process chain can be executed successfully even if there are
   * timeouts that do not apply
   */
  @Test
  fun noTimeoutApplies(vertx: Vertx, ctx: VertxTestContext) {
    val exec = Executable(path = "dummy", arguments = emptyList(),
      serviceId = "dummy", maxRuntime = TimeoutPolicy(2000),
      deadline = TimeoutPolicy(2000)
    )
    val processChain = ProcessChain(executables = listOf(exec))

    // return immediately before the timeouts can apply
    mockkConstructor(OtherRuntime::class)
    every { anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector) } just Runs

    val agent = LocalAgent(vertx, localAgentDispatcher)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // should succeed
        agent.execute(processChain, 1)

        // the executable should have been executed once
        verify(exactly = 1) {
          anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector)
        }
      }

      ctx.completeNow()
    }
  }

  private fun doTimeout(vertx: Vertx, ctx: VertxTestContext,
    maxRuntime: TimeoutPolicy?, deadline: TimeoutPolicy?, retries: RetryPolicy?,
    expectedType: String, expectedExceptionClass: Class<out Exception>,
    expectedCalls: Int) {
    val exec = Executable(path = "dummy", arguments = emptyList(),
      maxRuntime = maxRuntime, deadline = deadline,
      retries = retries, serviceId = "dummy")
    val processChain = ProcessChain(executables = listOf(exec))

    mockkConstructor(OtherRuntime::class)
    every { anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector) } answers {
      Thread.sleep(2000)
    }

    val agent = LocalAgent(vertx, localAgentDispatcher)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain, 1) }
          .isInstanceOf(expectedExceptionClass)
          .hasMessage("Execution of service `dummy' timed out after 200 ms ($expectedType)")

        verify(exactly = expectedCalls) {
          anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector)
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if configured timeouts do not affect how exceptions are
   * caught from the service execution
   */
  @Test
  fun maxRuntimeFailure(vertx: Vertx, ctx: VertxTestContext) {
    val exec = Executable(path = "dummy", arguments = emptyList(),
      serviceId = "dummy", maxRuntime = TimeoutPolicy(2000),
      deadline = TimeoutPolicy(2000)
    )
    val processChain = ProcessChain(executables = listOf(exec))

    // return immediately before the timeouts can apply
    mockkConstructor(OtherRuntime::class)
    val e = IllegalStateException("Dummy exception")
    every { anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector) } throws e

    val agent = LocalAgent(vertx, localAgentDispatcher)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain, 1) }.rootCause().isSameAs(e)
      }

      ctx.completeNow()
    }
  }

  private fun doMaxRuntime(vertx: Vertx, ctx: VertxTestContext,
      errorOnTimeout: Boolean, exceptionClass: Class<out Exception>) {
    doTimeout(vertx, ctx, TimeoutPolicy(200, errorOnTimeout = errorOnTimeout),
      null, null, "maximum runtime", exceptionClass, 1)
  }

  /**
   * Test if a process chain is cancelled due to a maximum runtime
   */
  @Test
  fun maxRuntime(vertx: Vertx, ctx: VertxTestContext) {
    doMaxRuntime(vertx, ctx, false, LocalAgent.TimeoutCancellationException::class.java)
  }

  /**
   * Test if a process chain is aborted (with an error) due to a maximum runtime
   */
  @Test
  fun maxRuntimeError(vertx: Vertx, ctx: VertxTestContext) {
    doMaxRuntime(vertx, ctx, true, LocalAgent.TimeoutException::class.java)
  }

  private fun doMaxRuntimeRetry(vertx: Vertx, ctx: VertxTestContext,
      errorOnTimeout: Boolean, exceptionClass: Class<out Exception>) {
    doTimeout(vertx, ctx, TimeoutPolicy(200, errorOnTimeout = errorOnTimeout),
      null, RetryPolicy(3), "maximum runtime", exceptionClass, 3)
  }

  /**
   * Test if the local agent still retries an executable even it is cancelled
   * due to a maximum runtime
   */
  @Test
  fun maxRuntimeRetry(vertx: Vertx, ctx: VertxTestContext) {
    doMaxRuntimeRetry(vertx, ctx, false, LocalAgent.TimeoutCancellationException::class.java)
  }

  /**
   * Test if the local agent still retries an executable even it is aborted
   * (with an error) due to a maximum runtime
   */
  @Test
  fun maxRuntimeRetryError(vertx: Vertx, ctx: VertxTestContext) {
    doMaxRuntimeRetry(vertx, ctx, true, LocalAgent.TimeoutException::class.java)
  }

  private fun doDeadline(vertx: Vertx, ctx: VertxTestContext,
    errorOnTimeout: Boolean, exceptionClass: Class<out Exception>) {
    doTimeout(vertx, ctx, null, TimeoutPolicy(200, errorOnTimeout = errorOnTimeout),
      null, "deadline", exceptionClass, 1)
  }

  /**
   * Test if a process chain is cancelled due to a deadline
   */
  @Test
  fun deadline(vertx: Vertx, ctx: VertxTestContext) {
    doDeadline(vertx, ctx, false, LocalAgent.TimeoutCancellationException::class.java)
  }

  /**
   * Test if a process chain is aborted (with an error) due to a deadline
   */
  @Test
  fun deadlineError(vertx: Vertx, ctx: VertxTestContext) {
    doDeadline(vertx, ctx, true, LocalAgent.TimeoutException::class.java)
  }

  private fun doDeadlineRetry(vertx: Vertx, ctx: VertxTestContext,
    errorOnTimeout: Boolean, exceptionClass: Class<out Exception>) {
    doTimeout(vertx, ctx, null, TimeoutPolicy(200, errorOnTimeout = errorOnTimeout),
      RetryPolicy(3), "deadline", exceptionClass, 1)
  }

  /**
   * Test that the local agent does not retry an executable if it was cancelled
   * due to a deadline
   */
  @Test
  fun deadlineRetry(vertx: Vertx, ctx: VertxTestContext) {
    doDeadlineRetry(vertx, ctx, false, LocalAgent.TimeoutCancellationException::class.java)
  }

  /**
   * Test that the local agent does not retry an executable if it was aborted
   * (with an error) due to a deadline
   */
  @Test
  fun deadlineRetryError(vertx: Vertx, ctx: VertxTestContext) {
    doDeadlineRetry(vertx, ctx, true, LocalAgent.TimeoutException::class.java)
  }

  /**
   * Test if a deadline can be earlier than the maximum runtime
   */
  @Test
  fun deadlineAndMaxRuntime(vertx: Vertx, ctx: VertxTestContext) {
    doTimeout(vertx, ctx, TimeoutPolicy(1000, errorOnTimeout = true), TimeoutPolicy(200),
      null, "deadline", LocalAgent.TimeoutCancellationException::class.java, 1)
  }

  /**
   * Test if a maximum runtime timeout can happen earlier than a deadline
   */
  @Test
  fun maxRuntimeAndDeadline(vertx: Vertx, ctx: VertxTestContext) {
    doTimeout(vertx, ctx, TimeoutPolicy(200), TimeoutPolicy(1000, errorOnTimeout = true),
      null, "maximum runtime", LocalAgent.TimeoutCancellationException::class.java, 1)
  }

  /**
   * Test that no timeout is triggered if the service produces output from time to time
   */
  @Test
  fun maxInactivityNoTimeout(vertx: Vertx, ctx: VertxTestContext) {
    val exec = Executable(path = "dummy", serviceId = "dummy",
        arguments = emptyList(), maxInactivity = TimeoutPolicy(200))
    val processChain = ProcessChain(executables = listOf(exec))

    mockkConstructor(OtherRuntime::class)
    val outputCollectorSlot = slot<OutputCollector>()
    every { anyConstructed<OtherRuntime>().execute(exec, capture(outputCollectorSlot)) } answers {
      for (i in 1..5) {
        Thread.sleep(100)
        outputCollectorSlot.captured.collect("$i")
      }
    }

    val agent = LocalAgent(vertx, localAgentDispatcher)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // should succeed
        agent.execute(processChain, 1)

        verify(exactly = 1) {
          anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector)
        }
      }

      ctx.completeNow()
    }
  }

  private fun doMaxInactivity(vertx: Vertx, ctx: VertxTestContext,
      retries: RetryPolicy?, expectedCalls: Int) {
    val exec = Executable(path = "dummy", serviceId = "dummy",
        arguments = emptyList(), retries = retries, maxInactivity = TimeoutPolicy(200))
    val processChain = ProcessChain(executables = listOf(exec))

    mockkConstructor(OtherRuntime::class)
    val outputCollectorSlot = slot<OutputCollector>()
    every { anyConstructed<OtherRuntime>().execute(exec, capture(outputCollectorSlot)) } answers {
      // be active
      for (i in 1..5) {
        Thread.sleep(100)
        outputCollectorSlot.captured.collect("$i")
      }
      // now be inactive
      Thread.sleep(1000)
    }

    val agent = LocalAgent(vertx, localAgentDispatcher)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // should succeed
        assertThatThrownBy { agent.execute(processChain, 1) }
          .isInstanceOf(LocalAgent.TimeoutCancellationException::class.java)
          .hasMessage("Execution of service `dummy' timed out after 200 ms (maximum inactivity)")

        verify(exactly = expectedCalls) {
          anyConstructed<OtherRuntime>().execute(exec, any() as OutputCollector)
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that the local agent cancels an executable if it was inactive for too long
   */
  @Test
  fun maxInactivity(vertx: Vertx, ctx: VertxTestContext) {
    doMaxInactivity(vertx, ctx, null, 1)
  }

  /**
   * Test that the local agent cancels an executable if it was inactive for too long
   * even after a few retries
   */
  @Test
  fun maxInactivityRetry(vertx: Vertx, ctx: VertxTestContext) {
    doMaxInactivity(vertx, ctx, RetryPolicy(3), 3)
  }

  /**
   * Test if the agent calls an input adapter plugin
   */
  @Test
  fun inputAdapter(vertx: Vertx, ctx: VertxTestContext) {
    val supportedDataType = "foobar"

    val customInputAdapter = spyk(object {
      @Suppress("UNUSED_PARAMETER")
      fun execute(input: Argument, executable: Executable,
          processChain: ProcessChain, vertx: Vertx): List<Argument> {
        return listOf(input.copy(label = "-a"))
      }
    })

    val pluginRegistry = mockk<PluginRegistry>()
    mockkObject(PluginRegistryFactory)
    every { PluginRegistryFactory.create() } returns pluginRegistry
    every { pluginRegistry.findInputAdapter(supportedDataType) } returns InputAdapterPlugin(
        name = "foobar",
        scriptFile = "",
        supportedDataType = supportedDataType,
        compiledFunction = customInputAdapter::execute
    )
    every { pluginRegistry.findProgressEstimator(any()) } returns null

    mockkConstructor(OtherRuntime::class)
    every { anyConstructed<OtherRuntime>().execute(any(), any() as OutputCollector) } just Runs

    val inputArg = Argument(
        variable = ArgumentVariable("id", "myValue"),
        type = Argument.Type.INPUT,
        dataType = supportedDataType
    )
    val modifiedArg = inputArg.copy(label = "-a")
    val exec = Executable(path = "ls", serviceId = "ls",
        arguments = listOf(inputArg))
    val modifiedExec = exec.copy(arguments = listOf(modifiedArg))
    val processChain = ProcessChain(executables = listOf(exec))

    val agent = createAgent(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        agent.execute(processChain, 1)
        verify(exactly = 1) {
          customInputAdapter.execute(inputArg, exec, processChain, any())
          anyConstructed<OtherRuntime>().execute(modifiedExec, any() as OutputCollector)
        }
      }
      ctx.completeNow()
    }
  }
}
