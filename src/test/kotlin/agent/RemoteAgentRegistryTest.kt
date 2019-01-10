package agent

import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
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
   * Test that no agent can be allocated if there are none
   */
  @Test
  fun allocateNoAgent(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      val agent = registry.allocate(ProcessChain())
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

    val nodeId = UniqueID.next()
    val agentId = UniqueID.next()
    val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId

    var addedCount = 0
    var availableCount = 0

    vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_AVAILABLE) { msg ->
      if (msg.body() == agentId) {
        availableCount++
      } else {
        ctx.failNow(NoStackTraceThrowable("Unknown agentId ${msg.body()}"))
      }
    }

    vertx.eventBus().consumer<String>(AddressConstants.REMOTE_AGENT_ADDED) { msg ->
      if (msg.body() == address) {
        addedCount++
      } else {
        ctx.failNow(NoStackTraceThrowable("Unknown agent address ${msg.body()}"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(nodeId, agentId)
      delay(200)
      ctx.verify {
        assertThat(addedCount).isEqualTo(1)
        assertThat(availableCount).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a single agent can be allocated and deallocated
   */
  @Test
  fun allocateDellocateOneAgent(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val nodeId = UniqueID.next()
    val agentId = UniqueID.next()
    val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId

    var inquiryCount = 0
    var allocateCount = 0
    var deallocateCount = 0

    vertx.eventBus().consumer<JsonObject>(address) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          msg.reply(json { obj("available" to true) })
          inquiryCount++
        }
        "allocate" -> {
          msg.reply("ACK")
          allocateCount++
        }
        "deallocate" -> {
          msg.reply("ACK")
          deallocateCount++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(nodeId, agentId)

      val agent = registry.allocate(ProcessChain())
      ctx.verify {
        assertThat(agent).isNotNull
        assertThat(agent!!.id).isEqualTo(address)
        assertThat(inquiryCount).isEqualTo(1)
        assertThat(allocateCount).isEqualTo(1)
        assertThat(deallocateCount).isEqualTo(0)
      }

      registry.deallocate(agent!!)
      ctx.verify {
        assertThat(inquiryCount).isEqualTo(1)
        assertThat(allocateCount).isEqualTo(1)
        assertThat(deallocateCount).isEqualTo(1)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that a single agent can be allocated and deallocated
   */
  @Test
  fun wrongCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap = setOf("docker", "gpu")
    val nodeId = UniqueID.next()
    val agentId = UniqueID.next()
    val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId

    vertx.eventBus().consumer<JsonObject>(address) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          ctx.verify {
            assertThat(json.getJsonArray("requiredCapabilities").toSet()).isEqualTo(reqCap)
          }
          msg.reply(json { obj("available" to false) })
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(nodeId, agentId)
      val agent = registry.allocate(ProcessChain(requiredCapabilities = reqCap))
      ctx.verify {
        assertThat(agent).isNull()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that agents can be allocated based on their capabilities
   */
  @Test
  fun capabilitiesBasedAllocation(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap1 = setOf("docker")
    val reqCap2 = setOf("gpu")

    val nodeId1 = UniqueID.next()
    val agentId1 = UniqueID.next()
    val address1 = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId1

    val nodeId2 = UniqueID.next()
    val agentId2 = UniqueID.next()
    val address2 = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId2

    var inquiryCount1 = 0
    var allocateCount1 = 0

    var inquiryCount2 = 0
    var allocateCount2 = 0

    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          val available = json.getJsonArray("requiredCapabilities").toSet() == reqCap1
          msg.reply(json { obj("available" to available) })
          inquiryCount1++
        }
        "allocate" -> {
          msg.reply("ACK")
          allocateCount1++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          val available = json.getJsonArray("requiredCapabilities").toSet() == reqCap2
          msg.reply(json { obj("available" to available) })
          inquiryCount2++
        }
        "allocate" -> {
          msg.reply("ACK")
          allocateCount2++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(nodeId1, agentId1)
      registry.register(nodeId2, agentId2)
      val agent1 = registry.allocate(ProcessChain(requiredCapabilities = reqCap1))
      ctx.verify {
        assertThat(agent1).isNotNull
        assertThat(agent1!!.id).isEqualTo(address1)
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(1)
        assertThat(allocateCount2).isEqualTo(0)
      }
      val agent2 = registry.allocate(ProcessChain(requiredCapabilities = reqCap2))
      ctx.verify {
        assertThat(agent2).isNotNull
        assertThat(agent2!!.id).isEqualTo(address2)
        assertThat(inquiryCount1).isEqualTo(2)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(2)
        assertThat(allocateCount2).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that agents with the same capabilities can be allocated based on
   * their last sequence
   */
  @Test
  fun sequenceBasedAllocation(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val nodeId1 = UniqueID.next()
    val agentId1 = UniqueID.next()
    val address1 = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId1

    val nodeId2 = UniqueID.next()
    val agentId2 = UniqueID.next()
    val address2 = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId2

    var inquiryCount1 = 0
    var allocateCount1 = 0

    var inquiryCount2 = 0
    var allocateCount2 = 0

    val q1 = ArrayDeque<Long>(listOf(0L, 1L, 4L))
    val q2 = ArrayDeque<Long>(listOf(2L, 2L, 3L))

    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          msg.reply(json {
            obj(
              "available" to true,
              "lastSequence" to q1.pop()
            )
          })
          inquiryCount1++
        }
        "allocate" -> {
          msg.reply("ACK")
          allocateCount1++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          msg.reply(json {
            obj(
                "available" to true,
                "lastSequence" to q2.pop()
            )
          })
          inquiryCount2++
        }
        "allocate" -> {
          msg.reply("ACK")
          allocateCount2++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(nodeId1, agentId1)
      registry.register(nodeId2, agentId2)
      val agent1 = registry.allocate(ProcessChain())
      ctx.verify {
        assertThat(agent1).isNotNull
        assertThat(agent1!!.id).isEqualTo(address1)
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(1)
        assertThat(allocateCount2).isEqualTo(0)
      }
      val agent2 = registry.allocate(ProcessChain())
      ctx.verify {
        assertThat(agent2).isNotNull
        assertThat(agent2!!.id).isEqualTo(address1)
        assertThat(inquiryCount1).isEqualTo(2)
        assertThat(allocateCount1).isEqualTo(2)
        assertThat(inquiryCount2).isEqualTo(2)
        assertThat(allocateCount2).isEqualTo(0)
      }
      val agent3 = registry.allocate(ProcessChain())
      ctx.verify {
        assertThat(agent3).isNotNull
        assertThat(agent3!!.id).isEqualTo(address2)
        assertThat(inquiryCount1).isEqualTo(3)
        assertThat(allocateCount1).isEqualTo(2)
        assertThat(inquiryCount2).isEqualTo(3)
        assertThat(allocateCount2).isEqualTo(1)
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

    val nodeId1 = UniqueID.next()
    val agentId1 = UniqueID.next()
    val address1 = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId1

    val nodeId2 = UniqueID.next()
    val agentId2 = UniqueID.next()
    val address2 = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + nodeId2

    var inquiryCount1 = 0
    var allocateCount1 = 0

    var inquiryCount2 = 0
    var allocateCount2 = 0

    vertx.eventBus().consumer<JsonObject>(address1) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          msg.reply(json {
            obj(
                "available" to true,
                "lastSequence" to 0L
            )
          })
          inquiryCount1++
        }
        "allocate" -> {
          msg.fail(503, "Sorry, but I'm actually busy. :-(")
          allocateCount1++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer<JsonObject>(address2) { msg ->
      val json = msg.body()
      val action = json.getString("action")
      when (action) {
        "inquire" -> {
          msg.reply(json {
            obj(
                "available" to true,
                "lastSequence" to 1L
            )
          })
          inquiryCount2++
        }
        "allocate" -> {
          msg.reply("ACK")
          allocateCount2++
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    GlobalScope.launch(vertx.dispatcher()) {
      registry.register(nodeId1, agentId1)
      registry.register(nodeId2, agentId2)
      val agent1 = registry.allocate(ProcessChain())
      ctx.verify {
        assertThat(agent1).isNotNull
        assertThat(agent1!!.id).isEqualTo(address2)
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(2)
        assertThat(allocateCount2).isEqualTo(1)
      }
      ctx.completeNow()
    }
  }
}
