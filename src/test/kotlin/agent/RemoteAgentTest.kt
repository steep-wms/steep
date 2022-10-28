package agent

import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import assertThatThrownBy
import coVerify
import db.SubmissionRegistry
import helper.CompressedJsonObjectMessageCodec
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.rmi.RemoteException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Tests for [RemoteAgent]
 * @author Michel Kraemer
 */
class RemoteAgentTest : AgentTest() {
  companion object {
    private const val NODE_ID = "RemoteAgentTest"
    private const val ADDRESS = REMOTE_AGENT_ADDRESS_PREFIX + NODE_ID
  }

  private val executorService = Executors.newCachedThreadPool()
  private val localAgentDispatcher = executorService.asCoroutineDispatcher()

  @BeforeEach
  fun setUp(vertx: Vertx) {
    vertx.eventBus().registerCodec(CompressedJsonObjectMessageCodec())
  }

  override fun createAgent(vertx: Vertx): Agent =
      RemoteAgent(ADDRESS, vertx)

  private fun registerConsumer(vertx: Vertx) {
    vertx.eventBus().consumer<JsonObject>(ADDRESS) consumer@ { msg ->
      val jsonObj: JsonObject = msg.body()
      val action: String = jsonObj["action"]
      if (action != "process") {
        msg.fail(400, "Unknown action: `$action'")
        return@consumer
      }

      val replyAddress: String = jsonObj["replyAddress"]
      val processChain = JsonUtils.fromJson<ProcessChain>(jsonObj["processChain"])
      val sequence: Long = jsonObj["sequence"]
      if (sequence != 0L) {
        msg.fail(400, "Wrong sequence number: $sequence")
        return@consumer
      }

      CoroutineScope(vertx.dispatcher()).launch {
        val la = LocalAgent(vertx, localAgentDispatcher)
        try {
          val results = la.execute(processChain)
          vertx.eventBus().send(replyAddress, json {
            obj(
                "results" to JsonUtils.toJson(results),
                "status" to SubmissionRegistry.ProcessChainStatus.SUCCESS.toString()
            )
          })
        } catch (t: Throwable) {
          vertx.eventBus().send(replyAddress, json {
            obj(
                "errorMessage" to t.message,
                "status" to SubmissionRegistry.ProcessChainStatus.ERROR.toString()
            )
          })
        }
      }

      msg.reply("ACK")
    }
  }

  @Test
  override fun execute(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    registerConsumer(vertx)
    super.execute(vertx, ctx, tempDir)
  }

  @Test
  override fun recursive(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    registerConsumer(vertx)
    super.recursive(vertx, ctx, tempDir)
  }

  @Test
  override fun customRuntime(vertx: Vertx, ctx: VertxTestContext) {
    registerConsumer(vertx)
    super.customRuntime(vertx, ctx)
  }

  @Test
  override fun customRuntimeThrows(vertx: Vertx, ctx: VertxTestContext) {
    registerConsumer(vertx)
    doCustomRuntimeThrows(vertx, ctx) { expected, actual ->
      actual is RemoteException && actual.message == expected.message
    }
  }

  @Test
  override fun retry(vertx: Vertx, ctx: VertxTestContext) {
    registerConsumer(vertx)
    super.retry(vertx, ctx)
  }

  /**
   * Test what happens if a remote agent does not accept the process chain
   */
  @Test
  fun doNotAck(vertx: Vertx, ctx: VertxTestContext) {
    vertx.eventBus().consumer<JsonObject>(ADDRESS) { msg ->
      msg.fail(400, "Unacknowledgeable")
    }

    val agent = createAgent(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(ProcessChain()) }
            .isInstanceOf(ReplyException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test what happens if a remote agent leaves the cluster
   */
  @Test
  fun abortOnLeave(vertx: Vertx, ctx: VertxTestContext) {
    vertx.eventBus().consumer<JsonObject>(ADDRESS) { msg ->
      // accept the process chain ...
      msg.reply("ACK")

      // but then leave the cluster
      vertx.eventBus().publish(CLUSTER_NODE_LEFT, jsonObjectOf(
          "agentId" to NODE_ID,
          "instances" to 1
      ))
    }

    val agent = createAgent(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(ProcessChain()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Agent left the cluster before process chain " +
                "execution could be finished")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test what happens if the remote agent returns an error message
   */
  @Test
  fun errorMessage(vertx: Vertx, ctx: VertxTestContext) {
    val errorMessage = UniqueID.next()

    vertx.eventBus().consumer<JsonObject>(ADDRESS) { msg ->
      // accept the process chain ...
      msg.reply("ACK")

      // but then send an error message
      val jsonObj: JsonObject = msg.body()
      val replyAddress: String = jsonObj["replyAddress"]
      vertx.eventBus().send(replyAddress, json {
        obj(
            "errorMessage" to errorMessage,
            "status" to SubmissionRegistry.ProcessChainStatus.ERROR.toString()
        )
      })
    }

    val agent = createAgent(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(ProcessChain()) }
            .isInstanceOf(RemoteException::class.java)
            .hasMessage(errorMessage)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test what happens if the remote agent cancels the execution
   */
  @Test
  fun cancel(vertx: Vertx, ctx: VertxTestContext) {
    vertx.eventBus().consumer<JsonObject>(ADDRESS) { msg ->
      // accept the process chain ...
      msg.reply("ACK")

      // but then cancel it
      val jsonObj: JsonObject = msg.body()
      val replyAddress: String = jsonObj["replyAddress"]
      vertx.eventBus().send(replyAddress, json {
        obj(
            "status" to SubmissionRegistry.ProcessChainStatus.CANCELLED.toString()
        )
      })
    }

    val agent = createAgent(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(ProcessChain()) }
            .isInstanceOf(CancellationException::class.java)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if the sequence is correctly incremented
   */
  @Test
  fun sequence(vertx: Vertx, ctx: VertxTestContext) {
    val q = ArrayDeque((0L..2L).toList())

    vertx.eventBus().consumer<JsonObject>(ADDRESS) consumer@ { msg ->
      val jsonObj: JsonObject = msg.body()
      val sequence: Long = jsonObj["sequence"]
      if (sequence != q.removeFirst()) {
        msg.fail(400, "Wrong sequence number: $sequence")
        return@consumer
      }

      val replyAddress: String = jsonObj["replyAddress"]
      vertx.eventBus().send(replyAddress, json {
        obj(
            "results" to obj(),
            "status" to SubmissionRegistry.ProcessChainStatus.SUCCESS.toString()
        )
      })

      msg.reply("ACK")
    }

    val agent = createAgent(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        agent.execute(ProcessChain())
        agent.execute(ProcessChain())
        agent.execute(ProcessChain())
        assertThat(q).isEmpty()
      }
      ctx.completeNow()
    }
  }
}
