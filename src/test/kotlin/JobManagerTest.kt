import agent.LocalAgent
import agent.RemoteAgentRegistry
import helper.Shell
import io.mockk.coEvery
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException
import java.rmi.RemoteException

/**
 * Tests for [JobManager]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class JobManagerTest {
  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    val config = json {
      obj(
          ConfigConstants.HTTP_ENABLED to false
      )
    }
    val options = DeploymentOptions(config)

    // deploy verticle under test
    vertx.deployVerticle(JobManager::class.qualifiedName, options, ctx.completing())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Execute an empty process chain
   */
  @Test
  fun executeEmptyProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      try {
        assertThat(agent).isNotNull
        requireNotNull(agent)

        val results = agent.execute(processChain)
        assertThat(results).isEmpty()

        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Execute a simple process chain
   */
  @Test
  fun executeProcessChain(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val expectedResults = mapOf("output_files" to listOf("test1", "test2"))

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } returns expectedResults

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      try {
        assertThat(agent).isNotNull
        requireNotNull(agent)

        val results = agent.execute(processChain)
        assertThat(results).isEqualTo(expectedResults)

        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Test what happens if [LocalAgent] throws an exception
   */
  @Test
  fun errorInLocalAgent(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val errorMessage = "File not found"

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } throws
        IOException(errorMessage)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      ctx.verify {
        assertThat(agent).isNotNull
      }

      try {
        agent!!.execute(processChain)
        ctx.failNow(NoStackTraceThrowable("Agent should throw"))
      } catch (e: RemoteException) {
        ctx.verify {
          assertThat(e).hasMessage(errorMessage)
        }
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }

  /**
   * Test what happens if [LocalAgent] throws a [LocalAgent.ExecutionException]
   */
  @Test
  fun executionExceptionInLocalAgent(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val errorMessage = "Could not generate file"
    val lastOutput = "This is the last output"
    val exitCode = 132

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } throws
        Shell.ExecutionException(errorMessage, lastOutput, exitCode)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = remoteAgentRegistry.allocate(processChain)
      ctx.verify {
        assertThat(agent).isNotNull
      }

      try {
        agent!!.execute(processChain)
        ctx.failNow(NoStackTraceThrowable("Agent should throw"))
      } catch (e: RemoteException) {
        ctx.verify {
          assertThat(e).hasMessage("$errorMessage\n\nExit code: $exitCode\n\n$lastOutput")
        }
        ctx.completeNow()
      } catch (t: Throwable) {
        ctx.failNow(t)
      }
    }
  }
}
