package agent

import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import assertThatThrownBy
import coVerify
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
        Executable(path = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "20"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    vertx.setTimer(200) {
      agent.cancel()
    }

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain) }
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
        Executable(path = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "20"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    vertx.setTimer(200) {
      vertx.eventBus().send(LOCAL_AGENT_ADDRESS_PREFIX + processChain.id, json {
        obj(
            "action" to "cancel"
        )
      })
    }

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThatThrownBy { agent.execute(processChain) }
            .isInstanceOf(CancellationException::class.java)
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
        Executable(path = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        )),
        Executable(path = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        )),
        Executable(path = "sleep", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), "1"),
                type = Argument.Type.INPUT)
        ))
    ))

    val agent = LocalAgent(vertx, localAgentDispatcher)

    vertx.setTimer(200) {
      val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
      GlobalScope.launch(vertx.dispatcher()) {
        ctx.coVerify {
          val msg = vertx.eventBus().requestAwait<Double?>(address, json {
            obj(
                "action" to "getProgress"
            )
          })
          assertThat(msg.body()).isNull()
        }
      }
    }

    vertx.setTimer(1400) {
      val address = LOCAL_AGENT_ADDRESS_PREFIX + processChain.id
      GlobalScope.launch(vertx.dispatcher()) {
        ctx.coVerify {
          val msg = vertx.eventBus().requestAwait<Double?>(address, json {
            obj(
                "action" to "getProgress"
            )
          })
          assertThat(msg.body()).isGreaterThan(0.0)
        }
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      agent.execute(processChain)
      ctx.completeNow()
    }
  }
}
