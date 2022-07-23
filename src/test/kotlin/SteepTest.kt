import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import agent.AgentRegistry.SelectCandidatesParam
import agent.LocalAgent
import agent.RemoteAgentRegistry
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.CompressedJsonObjectMessageCodec
import helper.Shell
import helper.UniqueID
import helper.hazelcast.ClusterMap
import helper.hazelcast.DummyClusterMap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.handler.HttpException
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.coroutines.toReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
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
    vertx.eventBus().registerCodec(CompressedJsonObjectMessageCodec())

    agentId = UniqueID.next()

    // mock hazelcast instance used by agent registry
    mockkObject(ClusterMap)
    every { ClusterMap.create<Any, Any>(any(), any()) } answers { DummyClusterMap(arg(0), arg(1)) }

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
          ConfigConstants.AGENT_BUSY_TIMEOUT to "1s",
          ConfigConstants.AGENT_ID to agentId,
          ConfigConstants.LOGS_PROCESSCHAINS_ENABLED to true,
          ConfigConstants.LOGS_PROCESSCHAINS_PATH to processChainLogPath
      )
    }
    val options = deploymentOptionsOf(config = config)
    vertx.deployVerticle(Steep::class.qualifiedName, options, ctx.succeedingThenComplete())
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent).isNotNull
        assertThatThrownBy { agent!!.execute(processChain) }
            .isInstanceOf(RemoteException::class.java)
            .hasMessage("$errorMessage\n\nExit code: $exitCode\n\n$lastOutput")
      }
      ctx.completeNow()
    }
  }

  /**
   * Execute a simple process chain and check if the operation is idempotent
   * (i.e. if we can call [agent.RemoteAgent.execute] multiple times but still
   * only execute the process chain once)
   */
  @Test
  fun executeProcessChainIdempotent(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val expectedResults = mapOf("output_files" to listOf("test1", "test2"))

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } coAnswers {
      // pretend it takes 1s to execute the process chain
      delay(1000)
      expectedResults
    }

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent).isNotNull

        val d1 = async {
          agent!!.execute(processChain)
        }
        val d2 = async {
          agent!!.execute(processChain)
        }

        val results1 = d1.await()
        val results2 = d2.await()

        assertThat(results1).isEqualTo(expectedResults)
        assertThat(results2).isEqualTo(expectedResults)

        // make sure `execute` was only called once
        coVerify(exactly = 1) {
          anyConstructed<LocalAgent>().execute(processChain)
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Execute a simple process chain and check if the operation is idempotent
   * even if we allocate the agent twice and have two different
   * [agent.RemoteAgent] instances
   */
  @Test
  fun executeProcessChainAllocateIdempotent(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)
    val expectedResults = mapOf("output_files" to listOf("test1", "test2"))

    mockkConstructor(LocalAgent::class)
    coEvery { anyConstructed<LocalAgent>().execute(processChain) } coAnswers {
      // pretend it takes 1s to execute the process chain
      delay(1000)
      expectedResults
    }

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent1 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent1).isNotNull

        val d1 = async {
          agent1!!.execute(processChain)
        }

        delay(500)

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent2).isNotNull
        val d2 = async {
          agent2!!.execute(processChain)
        }

        val results1 = d1.await()
        val results2 = d2.await()

        assertThat(results1).isEqualTo(expectedResults)
        assertThat(results2).isEqualTo(expectedResults)

        // make sure `execute` was only called once
        coVerify(exactly = 1) {
          anyConstructed<LocalAgent>().execute(processChain)
        }
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates).containsExactly(Pair(processChain.requiredCapabilities,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the agent selects the best required capabilities by maximum
   * priority and maximum count of corresponding process chains
   */
  @Test
  fun bestCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val requiredCapabilities1 = setOf("docker")
    val requiredCapabilities2 = setOf("gpu")
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates1 = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(requiredCapabilities1, 0, 0, 1),
          SelectCandidatesParam(requiredCapabilities2, 0, 0, 2)
      ))
      val candidates2 = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(requiredCapabilities1, 0, 0, 2),
          SelectCandidatesParam(requiredCapabilities2, 0, 0, 1)
      ))
      val candidates3 = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(requiredCapabilities1, 0, 0, 1),
          SelectCandidatesParam(requiredCapabilities2, 0, 0, 1)
      ))
      val candidates4 = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(requiredCapabilities1, 9, 10, 1),
          SelectCandidatesParam(requiredCapabilities2, 0, 9, 2)
      ))
      val candidates5 = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(requiredCapabilities1, 0, 0, 2),
          SelectCandidatesParam(requiredCapabilities2, 10, 100, 1)
      ))
      val candidates6 = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(requiredCapabilities1, 10, 100, 1),
          SelectCandidatesParam(requiredCapabilities2, 10, 100, 2)
      ))
      ctx.verify {
        assertThat(candidates1).containsExactly(Pair(requiredCapabilities2,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates2).containsExactly(Pair(requiredCapabilities1,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates3).containsExactly(Pair(requiredCapabilities1,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates4).containsExactly(Pair(requiredCapabilities1,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates5).containsExactly(Pair(requiredCapabilities2,
            REMOTE_AGENT_ADDRESS_PREFIX + agentId))
        assertThat(candidates6).containsExactly(Pair(requiredCapabilities2,
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
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(emptySet(), 0, 0, 1)
      ))
      ctx.coVerify {
        val agent1 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            UniqueID.next())
        assertThat(agent1).isNotNull

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            UniqueID.next())
        assertThat(agent2).isNull()

        remoteAgentRegistry.deallocate(agent1!!)
        val agent3 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            UniqueID.next())
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
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(emptySet(), 0, 0, 1)
      ))
      ctx.coVerify {
        val agent1 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            UniqueID.next())
        assertThat(agent1).isNotNull

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            UniqueID.next())
        assertThat(agent2).isNull()

        delay(1001)

        val agent3 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            UniqueID.next())
        assertThat(agent3).isNotNull
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can reallocate an agent if we send the same process chain ID again
   */
  @Test
  fun idempotentAllocate(vertx: Vertx, ctx: VertxTestContext) {
    val processChain = ProcessChain()
    val remoteAgentRegistry = RemoteAgentRegistry(vertx)

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent1 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent1).isNotNull

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent2).isNotNull
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

    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = remoteAgentRegistry.selectCandidates(listOf(
          SelectCandidatesParam(processChain.requiredCapabilities, 0, 0, 1)
      ))
      ctx.coVerify {
        val agent = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent).isNotNull
        agent!!.execute(processChain)

        delay(1001)

        val agent2 = remoteAgentRegistry.tryAllocate(candidates[0].second,
            processChain.id)
        assertThat(agent2).isNotNull
      }
      ctx.completeNow()
    }
  }

  private suspend fun receiveProcessChainLogFile(channel: ReceiveChannel<Message<JsonObject>>):
      Pair<String, JsonObject?> {
    var header: JsonObject? = null
    val receivedContents = StringBuilder()
    for (reply in channel) {
      val obj = reply.body()
      if (obj.isEmpty) {
        break
      } else if (obj.getLong("size") != null) {
        header = obj
        reply.reply(null)
      } else if (obj.getString("data") != null) {
        receivedContents.append(obj.getString("data"))
        reply.reply(null)
      } else if (obj.getInteger("error") != null) {
        throw HttpException(obj.getInteger("error"), obj.getString("message"))
      } else {
        throw IllegalStateException("Illegal message: ${obj.encode()}")
      }
    }

    return (receivedContents.toString() to header)
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

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toReceiveChannel(vertx)

        val msg = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress
          )
        }
        vertx.eventBus().send(address, msg)

        val (receivedContents, header) = receiveProcessChainLogFile(channel)

        val size = header?.getLong("size")
        assertThat(size).isEqualTo(contents.length.toLong())
        assertThat(receivedContents).isEqualTo(contents)

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

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toReceiveChannel(vertx)

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

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toReceiveChannel(vertx)

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

  /**
   * Test if we can partially fetch a process chain log file
   */
  @Test
  fun fetchProcessChainLogFilePartial(vertx: Vertx, ctx: VertxTestContext) {
    val id = "abcdefg123456"
    val contents = "Hello world"
    val logFile = File(processChainLogPath, "$id.log")
    logFile.writeText(contents)

    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId +
        REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
    val replyAddress = "$address.reply.${UniqueID.next()}"

    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        val channel = consumer.toReceiveChannel(vertx)

        // request the first byte
        val msg1 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 0,
              "end" to 0
          )
        }
        vertx.eventBus().send(address, msg1)

        val (receivedContents1, header1) = receiveProcessChainLogFile(channel)
        assertThat(header1).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 0L,
              "end" to 0L,
              "length" to 1L
          )
        })
        assertThat(receivedContents1).isEqualTo(contents.substring(0, 1))

        // request the first 5 bytes
        val msg2 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 0,
              "end" to 4
          )
        }
        vertx.eventBus().send(address, msg2)

        val (receivedContents2, header2) = receiveProcessChainLogFile(channel)
        assertThat(header2).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 0L,
              "end" to 4L,
              "length" to 5L
          )
        })
        assertThat(receivedContents2).isEqualTo(contents.substring(0, 5))

        // request 5 bytes in the middle
        val msg3 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 2,
              "end" to 6
          )
        }
        vertx.eventBus().send(address, msg3)

        val (receivedContents3, header3) = receiveProcessChainLogFile(channel)
        assertThat(header3).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 2L,
              "end" to 6L,
              "length" to 5L
          )
        })
        assertThat(receivedContents3).isEqualTo(contents.substring(2, 7))

        // request the last 5 bytes
        val msg4 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 6,
              "end" to 10
          )
        }
        vertx.eventBus().send(address, msg4)

        val (receivedContents4, header4) = receiveProcessChainLogFile(channel)
        assertThat(header4).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 6L,
              "end" to 10L,
              "length" to 5L
          )
        })
        assertThat(receivedContents4).isEqualTo(contents.substring(6))

        // request the last 5 bytes (end position 1 too high)
        val msg5 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 6,
              "end" to 11
          )
        }
        vertx.eventBus().send(address, msg5)

        val (receivedContents5, header5) = receiveProcessChainLogFile(channel)
        assertThat(header5).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 6L,
              "end" to 10L,
              "length" to 5L
          )
        })
        assertThat(receivedContents5).isEqualTo(contents.substring(6))

        // request the last 5 bytes (end position much too high)
        val msg6 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 6,
              "end" to 100
          )
        }
        vertx.eventBus().send(address, msg6)

        val (receivedContents6, header6) = receiveProcessChainLogFile(channel)
        assertThat(header6).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 6L,
              "end" to 10L,
              "length" to 5L
          )
        })
        assertThat(receivedContents6).isEqualTo(contents.substring(6))

        // request the last 5 bytes (no end position)
        val msg7 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 6
          )
        }
        vertx.eventBus().send(address, msg7)

        val (receivedContents7, header7) = receiveProcessChainLogFile(channel)
        assertThat(header7).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 6L,
              "end" to 10L,
              "length" to 5L
          )
        })
        assertThat(receivedContents7).isEqualTo(contents.substring(6))

        // send invalid request (end position less than start position)
        val msg8 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 6,
              "end" to 2
          )
        }
        vertx.eventBus().send(address, msg8)

        assertThatThrownBy { receiveProcessChainLogFile(channel) }
            .matches { it is HttpException && it.statusCode == 416}

        // send invalid request (start position out of bounds)
        val msg9 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 11
          )
        }
        vertx.eventBus().send(address, msg9)

        assertThatThrownBy { receiveProcessChainLogFile(channel) }
            .matches { it is HttpException && it.statusCode == 416}

        // request the last byte
        val msg10 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to 10,
              "end" to 10
          )
        }
        vertx.eventBus().send(address, msg10)

        val (receivedContents10, header10) = receiveProcessChainLogFile(channel)
        assertThat(header10).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 10L,
              "end" to 10L,
              "length" to 1L
          )
        })
        assertThat(receivedContents10).isEqualTo(contents.substring(contents.length - 1))

        // request the last byte (suffix request)
        val msg11 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to -1
          )
        }
        vertx.eventBus().send(address, msg11)

        val (receivedContents11, header11) = receiveProcessChainLogFile(channel)
        assertThat(header11).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 10L,
              "end" to 10L,
              "length" to 1L
          )
        })
        assertThat(receivedContents11).isEqualTo(contents.substring(contents.length - 1))

        // request the last 5 bytes (suffix request)
        val msg12 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to -5
          )
        }
        vertx.eventBus().send(address, msg12)

        val (receivedContents12, header12) = receiveProcessChainLogFile(channel)
        assertThat(header12).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 6L,
              "end" to 10L,
              "length" to 5L
          )
        })
        assertThat(receivedContents12).isEqualTo(contents.substring(contents.length - 5))

        // request too much (suffix request)
        val msg13 = json {
          obj(
              "action" to "fetch",
              "id" to id,
              "replyAddress" to replyAddress,
              "start" to -20
          )
        }
        vertx.eventBus().send(address, msg13)

        val (receivedContents13, header13) = receiveProcessChainLogFile(channel)
        assertThat(header13).isEqualTo(json {
          obj(
              "size" to 11L,
              "start" to 0L,
              "end" to 10L,
              "length" to 11L
          )
        })
        assertThat(receivedContents13).isEqualTo(contents)

        ctx.completeNow()
      }
    }
  }
}
