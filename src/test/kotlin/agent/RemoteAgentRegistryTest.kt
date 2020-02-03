package agent

import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import coVerify
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.ArrayDeque

/**
 * Tests for the [RemoteAgentRegistry]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class RemoteAgentRegistryTest {
  /**
   * A result of [registerAgentWithCapabilities]
   */
  private class RegisteredAgent(val address: String, var inquiryCount: Int = 0)

  /**
   * Registers a mock agent with the given [capabilities] in the given [registry].
   * An optional [sequenceProvider] can provide a value of `lastSequence` in
   * the `inquire` response, and a [bestSelector] selects the best supported
   * set of capabilities for this agent.
   */
  private suspend fun registerAgentWithCapabilities(capabilities: List<String>,
      registry: RemoteAgentRegistry, vertx: Vertx, ctx: VertxTestContext,
      sequenceProvider: (() -> Long)? = null,
      bestSelector: (JsonArray, List<String>) -> Int): RegisteredAgent {
    val agentId = UniqueID.next()
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId
    val result = RegisteredAgent(address)

    val handler = { msg: Message<JsonObject> ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          result.inquiryCount++
          val allRcs = json.getJsonArray("requiredCapabilities")
          val best = bestSelector(allRcs, capabilities)
          val available = best >= 0
          val reply = json {
            if (sequenceProvider != null) {
              obj(
                  "available" to available,
                  "bestRequiredCapabilities" to best,
                  "lastSequence" to sequenceProvider()
              )
            } else {
              obj(
                  "available" to available,
                  "bestRequiredCapabilities" to best
              )
            }
          }
          msg.reply(reply)
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer<JsonObject>(address, handler)
    registry.register(agentId)

    return result
  }

  /**
   * Test that no agent is selected as candidate if there are none
   */
  @Test
  fun selectNoCandidate(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      val candidates = registry.selectCandidates(listOf(emptySet()))
      ctx.verify {
        assertThat(candidates).isEmpty()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that no agent can be allocated if there are none
   */
  @Test
  fun allocateNoAgent(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      val agent = registry.tryAllocate("DUMMY_ADDRESS")
      ctx.verify {
        assertThat(agent).isNull()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a single agent can be registered
   */
  @Test
  fun register(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val agentId = UniqueID.next()
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId

    var addedCount = 0

    vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
      if (msg.body() == address) {
        addedCount++
      } else {
        ctx.failNow(NoStackTraceThrowable("Unknown agent address ${msg.body()}"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId)
      delay(200)
      ctx.verify {
        assertThat(addedCount).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a single agent can be selected as candidate
   */
  @Test
  fun selectOneCandidate(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = registerAgentWithCapabilities(emptyList(), registry, vertx, ctx) { allRcs, _ ->
        ctx.verify {
          assertThat(allRcs).isEqualTo(JsonArray().add(JsonArray()))
        }
        0
      }

      val candidates = registry.selectCandidates(listOf(emptySet()))
      ctx.verify {
        assertThat(candidates).hasSize(1)
        assertThat(candidates[0]).isEqualTo(Pair(emptySet<String>(), agent.address))
        assertThat(agent.inquiryCount).isEqualTo(1)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that a single agent can be allocated and deallocated
   */
  @Test
  fun allocateDeallocateOneAgent(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val agentId = UniqueID.next()
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId

    var allocateCount = 0
    var deallocateCount = 0

    vertx.eventBus().consumer<JsonObject>(address) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "allocate" -> {
          allocateCount++
          msg.reply("ACK")
        }
        "deallocate" -> {
          deallocateCount++
          msg.reply("ACK")
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId)

      val agent = registry.tryAllocate(address)
      ctx.verify {
        assertThat(agent).isNotNull
        assertThat(agent!!.id).isEqualTo(address)
        assertThat(allocateCount).isEqualTo(1)
        assertThat(deallocateCount).isEqualTo(0)
      }

      registry.deallocate(agent!!)
      ctx.verify {
        assertThat(allocateCount).isEqualTo(1)
        assertThat(deallocateCount).isEqualTo(1)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that no agent is selected if it rejects the given required capabilities
   */
  @Test
  fun selectNoAgentReject(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap = listOf("docker", "gpu")

    GlobalScope.launch(vertx.dispatcher()) {
      val agent = registerAgentWithCapabilities(reqCap, registry, vertx, ctx) { allRcs, capabilities ->
        ctx.verify {
          assertThat(allRcs).isEqualTo(JsonArray().add(JsonArray(capabilities)))
        }
        -1
      }

      val candidates = registry.selectCandidates(listOf(reqCap))
      ctx.verify {
        assertThat(candidates).isEmpty()
        assertThat(agent.inquiryCount).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that agents can be selected based on their capabilities
   */
  @Test
  fun capabilitiesBasedSelection(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap1 = listOf("docker")
    val reqCap2 = listOf("gpu")

    val bestSelector = { allRcs: JsonArray, capabilities: List<String> ->
      val available = allRcs == JsonArray().add(JsonArray(capabilities))
      if (available) 0 else -1
    }

    GlobalScope.launch(vertx.dispatcher()) {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx,
          ctx, bestSelector = bestSelector)

      val candidates1 = registry.selectCandidates(listOf(reqCap1))
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(reqCap1, agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }

      val candidates2 = registry.selectCandidates(listOf(reqCap2))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(reqCap2, agent2.address))
        assertThat(agent1.inquiryCount).isEqualTo(2)
        assertThat(agent2.inquiryCount).isEqualTo(2)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that multiple agents can be selected based on their capabilities
   */
  @Test
  fun capabilitiesBasedSelectionMultiple(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap1 = listOf("docker")
    val reqCap2 = listOf("gpu")
    val reqCap3 = listOf("docker", "gpu")

    val bestSelector = { allRcs: JsonArray, capabilities: List<String> ->
      allRcs.indexOfFirst { (it as JsonArray) == JsonArray(capabilities) }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent3 = registerAgentWithCapabilities(reqCap3, registry, vertx,
          ctx, bestSelector = bestSelector)

      val candidates = registry.selectCandidates(listOf(reqCap1, reqCap2, reqCap3))
      ctx.verify {
        assertThat(candidates).containsExactlyInAnyOrder(Pair(reqCap1, agent1.address),
            Pair(reqCap2, agent2.address), Pair(reqCap3, agent3.address))
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
        assertThat(agent3.inquiryCount).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that agents with the same capabilities can be selected based on
   * their last sequence
   */
  @Test
  fun sequenceBasedSelection(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val q1 = ArrayDeque<Long>(listOf(0L, 1L, 4L))
    val q2 = ArrayDeque<Long>(listOf(2L, 2L, 3L))

    GlobalScope.launch(vertx.dispatcher()) {
      val agent1 = registerAgentWithCapabilities(emptyList(), registry, vertx, ctx,
          sequenceProvider = q1::pop, bestSelector = { _,_ -> 0 })
      val agent2 = registerAgentWithCapabilities(emptyList(), registry, vertx, ctx,
          sequenceProvider = q2::pop, bestSelector = { _,_ -> 0 })

      val candidates1 = registry.selectCandidates(listOf(emptyList()))
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(emptyList<String>(), agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }
      val candidates2 = registry.selectCandidates(listOf(emptyList()))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(emptyList<String>(), agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(2)
        assertThat(agent2.inquiryCount).isEqualTo(2)
      }
      val candidates3 = registry.selectCandidates(listOf(emptyList()))
      ctx.verify {
        assertThat(candidates3).hasSize(1)
        assertThat(candidates3[0]).isEqualTo(Pair(emptyList<String>(), agent2.address))
        assertThat(agent1.inquiryCount).isEqualTo(3)
        assertThat(agent2.inquiryCount).isEqualTo(3)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that agents that first indicate that they are available but then
   * reject to be allocated are skipped
   */
  @Test
  fun skipBusy(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val agentId1 = UniqueID.next()
    val address1 = REMOTE_AGENT_ADDRESS_PREFIX + agentId1

    val agentId2 = UniqueID.next()
    val address2 = REMOTE_AGENT_ADDRESS_PREFIX + agentId2

    var inquiryCount1 = 0
    var allocateCount1 = 0

    var inquiryCount2 = 0
    var allocateCount2 = 0

    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          inquiryCount1++
          msg.reply(json {
            if (allocateCount1 > 0) {
              obj(
                  "available" to false
              )
            } else {
              obj(
                  "available" to true,
                  "bestRequiredCapabilities" to 0,
                  "lastSequence" to 0L
              )
            }
          })
        }
        "allocate" -> {
          allocateCount1++
          msg.fail(503, "Sorry, but I'm actually busy. :-(")
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          inquiryCount2++
          msg.reply(json {
            obj(
                "available" to true,
                "bestRequiredCapabilities" to 0,
                "lastSequence" to 1L
            )
          })
        }
        "allocate" -> {
          allocateCount2++
          msg.reply("ACK")
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId1)
      registry.register(agentId2)

      val candidates1 = registry.selectCandidates(listOf(emptyList()))
      ctx.coVerify {
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(0)
        assertThat(inquiryCount2).isEqualTo(1)
        assertThat(allocateCount2).isEqualTo(0)

        assertThat(candidates1).containsExactly(Pair(emptyList(), address1))
        val agent1 = registry.tryAllocate(address1)
        assertThat(agent1).isNull()

        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(1)
        assertThat(allocateCount2).isEqualTo(0)

        val candidates2 = registry.selectCandidates(listOf(emptyList()))
        assertThat(candidates2).containsExactly(Pair(emptyList(), address2))
        val agent2 = registry.tryAllocate(address2)
        assertThat(agent2).isNotNull

        assertThat(inquiryCount1).isEqualTo(2)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(2)
        assertThat(allocateCount2).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }
}
