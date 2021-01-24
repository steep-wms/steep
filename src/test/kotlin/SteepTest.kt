import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import agent.LocalAgent
import agent.RemoteAgentRegistry
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.Shell
import helper.UniqueID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toChannel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.rmi.RemoteException

/**
 * Tests for [Steep]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class SteepTest {
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var agentId: String
  private lateinit var processChainLogPath: String

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    agentId = UniqueID.next()

    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry

    val processChainLogPathFile = File(tempDir.toFile(), "processchains")
    processChainLogPathFile.mkdirs()
    processChainLogPath = processChainLogPathFile.absolutePath

    // deploy verticle under test
    val config = json {
      obj(
          ConfigConstants.AGENT_CAPABILTIIES to array("docker", "gpu"),
          ConfigConstants.AGENT_BUSY_TIMEOUT to 1L,
          ConfigConstants.AGENT_ID to agentId,
          ConfigConstants.LOGS_PROCESSCHAINS_ENABLED to true,
          ConfigConstants.LOGS_PROCESSCHAINS_PATH to processChainLogPath
      )
    }
    val options = deploymentOptionsOf(config)
    vertx.deployVerticle(Steep::class.qualifiedName, options, ctx.completing())
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
          listOf(processChain.requiredCapabilities to 1))
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
          listOf(processChain.requiredCapabilities to 1))
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
          listOf(processChain.requiredCapabilities to 1))
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
          listOf(processChain.requiredCapabilities to 1))
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
    val processChain = ProcessChain(requiredCapabilities = setOf("docker", "foobar"))
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = remoteAgentRegistry.selectCandidates(
          listOf(processChain.requiredCapabilities to 1))
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
          listOf(processChain.requiredCapabilities to 1))
      ctx.verify {
        assertThat(candidates).containsExactly(Pair(processChain.requiredCapabilities,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the agent selects the best required capabilities by maximum count
   * of corresponding process chains
   */
  @Test
  fun bestCapabilitiesByCount(vertx: Vertx, ctx: VertxTestContext) {
    val requiredCapabilities1 = setOf("docker")
    val requiredCapabilities2 = setOf("gpu")
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val candidates1 = remoteAgentRegistry.selectCandidates(
          listOf(requiredCapabilities1 to 1, requiredCapabilities2 to 2))
      val candidates2 = remoteAgentRegistry.selectCandidates(
          listOf(requiredCapabilities1 to 2, requiredCapabilities2 to 1))
      val candidates3 = remoteAgentRegistry.selectCandidates(
          listOf(requiredCapabilities1 to 1, requiredCapabilities2 to 1))
      ctx.verify {
        assertThat(candidates1).containsExactly(Pair(requiredCapabilities2,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates2).containsExactly(Pair(requiredCapabilities1,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates3).containsExactly(Pair(requiredCapabilities1,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
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
          listOf(processChain.requiredCapabilities to 1))
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
          listOf(processChain.requiredCapabilities to 1))
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
          listOf(processChain.requiredCapabilities to 1))
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

  /**
   * Test if we can fetch a process chain log file
   */
  @Test
  fun fetchProcessChainLogFile(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdefg123456"
    val contents = "Hello world"
    val logFile = File(processChainLogPath, "$id.log")
    logFile.writeText(contents)

    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    val replyAddress = "$address.reply.${UniqueID.next()}"

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toChannel(vertx)

        val msg = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress
          )
        }
        vertx.eventBus().send(address, msg)

        var size: Long? = null
        val receivedContents = StringBuilder()
        for (reply in channel) {
          val obj = reply.body()
          if (obj.isEmpty) {
            break
          } else if (obj.getLong("size") != null) {
            size = obj.getLong("size")
          } else if (obj.getString("data") != null) {
            receivedContents.append(obj.getString("data"))
            reply.reply(null)
          } else {
            ctx.failNow(IllegalStateException("Illegal message: ${obj.encode()}"))
          }
        }

        assertThat(size).isEqualTo(contents.length.toLong())
        assertThat(receivedContents.toString()).isEqualTo(contents)

        ctx.completeNow()
      }
    }
  }

  /**
   * Test if we can check if a process chain log file exists
   */
  @Test
  fun existsProcessChainLogFile(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdefg123456"
    val contents = "Hello world"
    val logFile = File(processChainLogPath, "$id.log")
    logFile.writeText(contents)

    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    val replyAddress = "$address.reply.${UniqueID.next()}"

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toChannel(vertx)

        val msg = json {
          obj(
              "action" to "exists",
              "id" to id,
              "replyAddress" to replyAddress
          )
        }
        vertx.eventBus().send(address, msg)

        val obj = channel.receive().body()
        assertThat(obj.getInteger("size")).isEqualTo(contents.length.toLong())

        ctx.completeNow()
      }
    }
  }

  /**
   * Test if we fetching a process chain log file fails if it does not exist
   */
  @Test
  fun fetchNonExistingProcessChainLogFile(vertx: Vertx, ctx: VertxTestContext) {
    val id = "FOOBAR"

    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    val replyAddress = "$address.reply.${UniqueID.next()}"

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toChannel(vertx)

        val msg = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress
          )
        }
        vertx.eventBus().send(address, msg)

        val obj = channel.receive().body()
        assertThat(obj.getInteger("error")).isEqualTo(404)

        ctx.completeNow()
      }
    }
  }
}
