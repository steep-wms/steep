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

    val agentId = UniqueID.next()
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId

    var inquiryCount = 0

    vertx.eventBus().consumer<JsonObject>(address) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          ctx.verify {
            assertThat(json.getJsonArray("requiredCapabilities")).isEqualTo(
                JsonArray().add(JsonArray()))
          }
          inquiryCount++
          msg.reply(json {
            obj(
                "available" to true,
                "bestRequiredCapabilities" to 0
            )
          })
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId)

      val candidates = registry.selectCandidates(listOf(emptySet()))
      ctx.verify {
        assertThat(candidates).hasSize(1)
        assertThat(candidates[0]).isEqualTo(Pair(emptySet<String>(), address))
        assertThat(inquiryCount).isEqualTo(1)
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

    val reqCap = setOf("docker", "gpu")
    val agentId = UniqueID.next()
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId

    vertx.eventBus().consumer<JsonObject>(address) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          ctx.verify {
            assertThat(json.getJsonArray("requiredCapabilities")).isEqualTo(
                JsonArray().add(JsonArray(reqCap.toList())))
          }
          msg.reply(json { obj("available" to false) })
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId)
      val agent = registry.selectCandidates(listOf(reqCap))
      ctx.verify {
        assertThat(agent).isEmpty()
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

    val agentId1 = UniqueID.next()
    val address1 = REMOTE_AGENT_ADDRESS_PREFIX + agentId1

    val agentId2 = UniqueID.next()
    val address2 = REMOTE_AGENT_ADDRESS_PREFIX + agentId2

    var inquiryCount1 = 0
    var inquiryCount2 = 0

    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          inquiryCount1++
          val available = json.getJsonArray("requiredCapabilities") ==
              JsonArray().add(JsonArray(reqCap1))
          val reply = json {
            if (available) {
              obj(
                  "available" to true,
                  "bestRequiredCapabilities" to 0
              )
            } else {
              obj("available" to false)
            }
          }
          msg.reply(reply)
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          inquiryCount2++
          val available = json.getJsonArray("requiredCapabilities") ==
              JsonArray().add(JsonArray(reqCap2))
          val reply = json {
            if (available) {
              obj(
                  "available" to true,
                  "bestRequiredCapabilities" to 0
              )
            } else {
              obj("available" to false)
            }
          }
          msg.reply(reply)
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId1)
      registry.register(agentId2)
      val candidates1 = registry.selectCandidates(listOf(reqCap1))
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(reqCap1, address1))
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(1)
      }
      val candidates2 = registry.selectCandidates(listOf(reqCap2))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(reqCap2, address2))
        assertThat(inquiryCount1).isEqualTo(2)
        assertThat(inquiryCount2).isEqualTo(2)
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

    val agentId1 = UniqueID.next()
    val address1 = REMOTE_AGENT_ADDRESS_PREFIX + agentId1

    val agentId2 = UniqueID.next()
    val address2 = REMOTE_AGENT_ADDRESS_PREFIX + agentId2

    val agentId3 = UniqueID.next()
    val address3 = REMOTE_AGENT_ADDRESS_PREFIX + agentId3

    val handler = { reqCap: List<String> -> { msg: Message<JsonObject> ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          val allRcs = json.getJsonArray("requiredCapabilities")
          val best = allRcs.indexOfFirst { (it as JsonArray) == JsonArray(reqCap) }
          val reply = json {
            obj(
                "available" to true,
                "bestRequiredCapabilities" to best
            )
          }
          msg.reply(reply)
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }}

    vertx.eventBus().consumer<JsonObject>(address1, handler(reqCap1))
    vertx.eventBus().consumer<JsonObject>(address2, handler(reqCap2))
    vertx.eventBus().consumer<JsonObject>(address3, handler(reqCap3))

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId1)
      registry.register(agentId2)
      registry.register(agentId3)
      val candidates = registry.selectCandidates(listOf(reqCap1, reqCap2, reqCap3))
      ctx.verify {
        assertThat(candidates).containsExactlyInAnyOrder(Pair(reqCap1, address1),
            Pair(reqCap2, address2), Pair(reqCap3, address3))
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

    val agentId1 = UniqueID.next()
    val address1 = REMOTE_AGENT_ADDRESS_PREFIX + agentId1

    val agentId2 = UniqueID.next()
    val address2 = REMOTE_AGENT_ADDRESS_PREFIX + agentId2

    var inquiryCount1 = 0
    var inquiryCount2 = 0

    val q1 = ArrayDeque<Long>(listOf(0L, 1L, 4L))
    val q2 = ArrayDeque<Long>(listOf(2L, 2L, 3L))

    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          inquiryCount1++
          msg.reply(json {
            obj(
                "available" to true,
                "lastSequence" to q1.pop(),
                "bestRequiredCapabilities" to 0
            )
          })
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
                "lastSequence" to q2.pop(),
                "bestRequiredCapabilities" to 0
            )
          })
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(agentId1)
      registry.register(agentId2)
      val candidates1 = registry.selectCandidates(listOf(emptyList()))
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(emptyList<String>(), address1))
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(1)
      }
      val candidates2 = registry.selectCandidates(listOf(emptyList()))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(emptyList<String>(), address1))
        assertThat(inquiryCount1).isEqualTo(2)
        assertThat(inquiryCount2).isEqualTo(2)
      }
      val candidates3 = registry.selectCandidates(listOf(emptyList()))
      ctx.verify {
        assertThat(candidates3).hasSize(1)
        assertThat(candidates3[0]).isEqualTo(Pair(emptyList<String>(), address2))
        assertThat(inquiryCount1).isEqualTo(3)
        assertThat(inquiryCount2).isEqualTo(3)
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
