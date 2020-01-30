import agent.LocalAgent
import agent.RemoteAgentRegistry
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.Shell
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
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
  private lateinit var submissionRegistry: SubmissionRegistry

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.AGENT_CAPABILTIIES to array("docker"),
          ConfigConstants.AGENT_BUSY_TIMEOUT to 1L
      )
    }
    val options = DeploymentOptions(config)
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
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent).isNotNull

        val results = agent!!.execute(processChain)
        assertThat(results).isEmpty()
      }
      ctx.completeNow()
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
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent).isNotNull

        val results = agent!!.execute(processChain)
        assertThat(results).isEqualTo(expectedResults)
      }
      ctx.completeNow()
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
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThatThrownBy { agent!!.execute(processChain) }
            .isInstanceOf(RemoteException::class.java)
            .hasMessage(errorMessage)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test what happens if [LocalAgent] throws a [Shell.ExecutionException]
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
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent).isNotNull
        assertThatThrownBy { agent!!.execute(processChain) }
            .isInstanceOf(RemoteException::class.java)
            .hasMessage("$errorMessage\n\nExit code: $exitCode\n\n$lastOutput")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test what happens if the agent does not have the required capabilities
   */
  @Test
  fun wrongCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain(requiredCapabilities = setOf("docker", "gpu"))
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.verify {
        assertThat(candidates).isEmpty()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we are available if the required capabilities match
   */
  @Test
  fun rightCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain(requiredCapabilities = setOf("docker"))
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.verify {
        assertThat(candidates).containsExactly(Pair(processChain.requiredCapabilities,
            AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX + Main.agentId))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can be allocated and deallocated
   */
  @Test
  fun allocateDeallocate(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent1 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent1).isNotNull

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent2).isNull()

        remoteAgentRegistry.deallocate(agent1!!)
        val agent3 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent3).isNotNull
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the agent becomes available again after not having received a
   * process chain for too long
   */
  @Test
  fun idle(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent1 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent1).isNotNull

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent2).isNull()

        delay(1001)

        val agent3 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent3).isNotNull
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the agent becomes available again after being idle for too long
   */
  @Test
  fun idleAfterExecute(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } returns emptyMap()

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent).isNotNull
        agent!!.execute(processChain)

        delay(1001)

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second)
        assertThat(agent2).isNotNull
      }
      ctx.completeNow()
    }
  }
}
