package agent

import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import agent.AgentRegistry.SelectCandidatesParam
import coVerify
import helper.UniqueID
import helper.hazelcast.ClusterMap
import helper.hazelcast.DummyClusterMap
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

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

  @BeforeEach
  fun setUp() {
    mockkObject(ClusterMap)
    every { ClusterMap.create<Any, Any>(any(), any()) } answers { DummyClusterMap(arg(0), arg(1)) }
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Registers a mock agent with the given [capabilities] in the given [registry].
   * An optional [sequenceProvider] can provide a value of `lastSequence` in
   * the `inquire` response, and a [bestSelector] selects the best supported
   * set of capabilities for this agent.
   */
  private suspend fun registerAgentWithCapabilities(capabilities: List<String>,
      registry: RemoteAgentRegistry, vertx: Vertx, ctx: VertxTestContext,
      agentId: String = UniqueID.next(),
      sequenceProvider: (() -> Long)? = null,
      bestSelector: (JsonArray, List<String>, List<Long>) -> Int): RegisteredAgent {
    val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId
    val result = RegisteredAgent(address)

    val handler = { msg: Message<JsonObject> ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "inquire" -> {
          result.inquiryCount++
          val allRcs = JsonArray()
          val allCounts = mutableListOf<Long>()
          json.getJsonArray("params").forEach {
            val obj = it as JsonObject
            allRcs.add(obj.getJsonArray("requiredCapabilities"))
            allCounts.add(obj.getLong("count", 0L))
          }
          val includeCapabilities = json.getBoolean("includeCapabilities", false)
          val best = bestSelector(allRcs, capabilities, allCounts)
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
          if (includeCapabilities) {
            val capsArr = JsonArray()
            capabilities.forEach { capsArr.add(it) }
            reply.put("capabilities", capsArr)
          }
          msg.reply(reply)
        }
        else -> ctx.failNow(NoStackTraceThrowable("Unknown action $action"))
      }
    }

    vertx.eventBus().consumer(address, handler)
    registry.register(agentId)

    return result
  }

  /**
   * Test that no agent is selected as candidate if there are none
   */
  @Test
  fun selectNoCandidate(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)
    CoroutineScope(vertx.dispatcher()).launch {
      val candidates = registry.selectCandidates(listOf(
          SelectCandidatesParam(emptySet(), 0, 0, 1)
      ))
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
    CoroutineScope(vertx.dispatcher()).launch {
      val agent = registry.tryAllocate("DUMMY_ADDRESS", UniqueID.next())
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

    var addedCount = 0

    vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
      if (msg.body() == agentId) {
        addedCount++
      } else {
        ctx.failNow(NoStackTraceThrowable("Unknown agent id ${msg.body()}"))
      }
    }

    CoroutineScope(vertx.dispatcher()).launch {
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

    CoroutineScope(vertx.dispatcher()).launch {
      val agent = registerAgentWithCapabilities(emptyList(), registry, vertx, ctx) { allRcs, _, _ ->
        ctx.verify {
          assertThat(allRcs).isEqualTo(JsonArray().add(JsonArray()))
        }
        0
      }

      val candidates = registry.selectCandidates(listOf(
          SelectCandidatesParam(emptySet(), 0, 0, 1)
      ))
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
    val processChainId = UniqueID.next()

    var allocateCount = 0
    var deallocateCount = 0

    vertx.eventBus().consumer<JsonObject>(address) { msg ->
      val json = msg.body()
      when (val action = json.getString("action")) {
        "allocate" -> {
          ctx.verify {
            assertThat(json.getString("processChainId")).isEqualTo(processChainId)
          }
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

    CoroutineScope(vertx.dispatcher()).launch {
      registry.register(agentId)

      val agent = registry.tryAllocate(address, processChainId)
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

    CoroutineScope(vertx.dispatcher()).launch {
      val agent = registerAgentWithCapabilities(reqCap, registry, vertx, ctx) { allRcs, capabilities, _ ->
        ctx.verify {
          assertThat(allRcs).isEqualTo(JsonArray().add(JsonArray(capabilities)))
        }
        -1
      }

      val candidates = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap, 0, 0, 1)
      ))
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

    val bestSelector = { allRcs: JsonArray, capabilities: List<String>, _: List<Long> ->
      val available = allRcs == JsonArray().add(JsonArray(capabilities))
      if (available) 0 else -1
    }

    CoroutineScope(vertx.dispatcher()).launch {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx,
          ctx, bestSelector = bestSelector)

      val candidates1 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap1, 0, 0, 1)
      ))
      var agent2InquiryCount1 = -1
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(reqCap1, agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(1)
        // agent 2 will either be asked or not (depending on the order of the
        // agents in the registry's internal list)
        agent2InquiryCount1 = agent2.inquiryCount
        assertThat(agent2InquiryCount1).isBetween(0, 1)
      }

      val candidates2 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap2, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(reqCap2, agent2.address))
        // agent 1 will either be asked or not
        assertThat(agent1.inquiryCount).isBetween(1, 2)
        // agent 2 should definitely now have been inquired
        assertThat(agent2.inquiryCount).isEqualTo(agent2InquiryCount1 + 1)
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

    val bestSelector = { allRcs: JsonArray, capabilities: List<String>, _: List<Long> ->
      allRcs.indexOfFirst { (it as JsonArray) == JsonArray(capabilities) }
    }

    CoroutineScope(vertx.dispatcher()).launch {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent3 = registerAgentWithCapabilities(reqCap3, registry, vertx,
          ctx, bestSelector = bestSelector)

      val candidates = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap1, 0, 0, 1),
          SelectCandidatesParam(reqCap2, 0, 0, 1),
          SelectCandidatesParam(reqCap3, 0, 0, 1)
      ))
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
   * Test that agents can be selected based on their capabilities if the
   * registry already knows what they support
   */
  @Test
  fun capabilitiesBasedSelectionWithCache(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap1 = listOf("docker")
    val reqCap2 = listOf("gpu")

    val bestSelector = { allRcs: JsonArray, capabilities: List<String>, _: List<Long> ->
      val available = allRcs == JsonArray().add(JsonArray(capabilities))
      if (available) 0 else -1
    }

    CoroutineScope(vertx.dispatcher()).launch {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx,
          ctx, bestSelector = bestSelector)
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx,
          ctx, bestSelector = bestSelector)

      // ask for capabilities that are not supported so the registry will
      // have to ask all agents and then know about all supported capabilities
      val candidates0 = registry.selectCandidates(listOf(
          SelectCandidatesParam(listOf("foobar"), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates0).isEmpty()
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }

      val candidates1 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap1, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(reqCap1, agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(2)
        // agent 2 should never be asked now because it does not support
        // reqCap1 and the registry should know that
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }

      val candidates2 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap2, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(reqCap2, agent2.address))
        // agent 1 should never be asked now
        assertThat(agent1.inquiryCount).isEqualTo(2)
        assertThat(agent2.inquiryCount).isEqualTo(2)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that no agent will be selected if the capabilities are not supported
   */
  @Test
  fun capabilitiesBasedSelectionNone(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val reqCap1 = listOf("docker")
    val reqCap2 = listOf("gpu")

    CoroutineScope(vertx.dispatcher()).launch {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx,
          ctx, bestSelector = { _, _, _ -> -1 })
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx,
          ctx, bestSelector = { _, _, _ -> -1 })

      val candidates1 = registry.selectCandidates(listOf(
          SelectCandidatesParam(listOf("foobar"), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates1).isEmpty()
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }

      val candidates2 = registry.selectCandidates(listOf(
          SelectCandidatesParam(listOf("foobar"), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates2).isEmpty()
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
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

    val q1 = ArrayDeque(listOf(0L, 1L, 4L))
    val q2 = ArrayDeque(listOf(2L, 2L, 3L))

    CoroutineScope(vertx.dispatcher()).launch {
      val agent1 = registerAgentWithCapabilities(emptyList(), registry, vertx, ctx,
          sequenceProvider = q1::removeFirst, bestSelector = { _, _, _ -> 0 })
      val agent2 = registerAgentWithCapabilities(emptyList(), registry, vertx, ctx,
          sequenceProvider = q2::removeFirst, bestSelector = { _, _, _ -> 0 })

      val candidates1 = registry.selectCandidates(listOf(
          SelectCandidatesParam(emptyList(), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates1).hasSize(1)
        assertThat(candidates1[0]).isEqualTo(Pair(emptyList<String>(), agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }
      val candidates2 = registry.selectCandidates(listOf(
          SelectCandidatesParam(emptyList(), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates2).hasSize(1)
        assertThat(candidates2[0]).isEqualTo(Pair(emptyList<String>(), agent1.address))
        assertThat(agent1.inquiryCount).isEqualTo(2)
        // agent 2 should not be inquired here because agent 1 is available and
        // has a lower sequence number
        assertThat(agent2.inquiryCount).isEqualTo(1)
      }
      val candidates3 = registry.selectCandidates(listOf(
          SelectCandidatesParam(emptyList(), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates3).hasSize(1)
        assertThat(candidates3[0]).isEqualTo(Pair(emptyList<String>(), agent2.address))
        assertThat(agent1.inquiryCount).isEqualTo(3)
        assertThat(agent2.inquiryCount).isEqualTo(2)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that multiple agents can be selected based on their capabilities
   * and last sequence
   */
  @Test
  fun capabilitiesAndSequence(vertx: Vertx, ctx: VertxTestContext) {
    val registry = RemoteAgentRegistry(vertx)

    val q1 = ArrayDeque(listOf(0L, 1L, 5L, 7L))
    val q2 = ArrayDeque(listOf(4L,     4L, 8L))
    val q3 = ArrayDeque(listOf(2L, 3L, 9L    ))

    val reqCap1 = listOf("docker")
    val reqCap2 = listOf("gpu")
    val reqCap3 = listOf("docker", "gpu")

    val bestSelector = { allRcs: JsonArray, capabilities: List<String>, allCounts: List<Long> ->
      var result = -1
      for (i in 0 until allRcs.size()) {
        if (allCounts[i] == 0L) {
          continue
        }
        val rcs = allRcs.getJsonArray(i).map { s -> s as String }
        if (capabilities.containsAll(rcs)) {
          result = i
          break
        }
      }
      result
    }

    CoroutineScope(vertx.dispatcher()).launch {
      val agent1 = registerAgentWithCapabilities(reqCap1, registry, vertx, ctx,
          agentId = "A", sequenceProvider = q1::removeFirst, bestSelector = bestSelector)
      val agent2 = registerAgentWithCapabilities(reqCap2, registry, vertx, ctx,
          agentId = "B", sequenceProvider = q2::removeFirst, bestSelector = bestSelector)
      val agent3 = registerAgentWithCapabilities(reqCap3, registry, vertx, ctx,
          agentId = "C", sequenceProvider = q3::removeFirst, bestSelector = bestSelector)

      // ask for capabilities that are not supported to initialize cache
      val candidates0 = registry.selectCandidates(listOf(
          SelectCandidatesParam(listOf("foobar"), 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates0).isEmpty()
        assertThat(agent1.inquiryCount).isEqualTo(1)
        assertThat(agent2.inquiryCount).isEqualTo(1)
        assertThat(agent3.inquiryCount).isEqualTo(1)
      }

      val candidates1 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap1, 0, 0, 1),
          SelectCandidatesParam(reqCap2, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates1).containsExactlyInAnyOrder(Pair(reqCap1, agent1.address),
            Pair(reqCap2, agent3.address))
        assertThat(agent1.inquiryCount).isEqualTo(2)
        // agent 2 should not have been asked because of its last sequence
        assertThat(agent2.inquiryCount).isEqualTo(1)
        assertThat(agent3.inquiryCount).isEqualTo(2)
      }

      val candidates2 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap1, 0, 0, 1),
          SelectCandidatesParam(reqCap2, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates2).containsExactlyInAnyOrder(Pair(reqCap1, agent1.address),
            Pair(reqCap2, agent3.address))
        assertThat(agent1.inquiryCount).isEqualTo(3)
        // agent 2 should now have been asked because its last sequence is now
        // lower than that of agent 3
        assertThat(agent2.inquiryCount).isEqualTo(2)
        // agent 3 should also have been asked because it had a lower last
        // sequence than agent 2
        assertThat(agent3.inquiryCount).isEqualTo(3)
      }

      val candidates3 = registry.selectCandidates(listOf(
          SelectCandidatesParam(reqCap1, 0, 0, 1),
          SelectCandidatesParam(reqCap2, 0, 0, 1)
      ))
      ctx.verify {
        assertThat(candidates3).containsExactlyInAnyOrder(Pair(reqCap1, agent1.address),
            Pair(reqCap2, agent2.address))
        assertThat(agent1.inquiryCount).isEqualTo(4)
        assertThat(agent2.inquiryCount).isEqualTo(3)
        // agent 3 should now not have been asked because its last sequence
        // was too high
        assertThat(agent3.inquiryCount).isEqualTo(3)
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

    val processChainId = UniqueID.next()

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
          ctx.verify {
            assertThat(json.getString("processChainId")).isEqualTo(processChainId)
          }
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

    CoroutineScope(vertx.dispatcher()).launch {
      registry.register(agentId1)
      registry.register(agentId2)

      val candidates1 = registry.selectCandidates(listOf(
          SelectCandidatesParam(emptyList(), 0, 0, 1)
      ))
      ctx.coVerify {
        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(0)
        assertThat(inquiryCount2).isEqualTo(1)
        assertThat(allocateCount2).isEqualTo(0)

        assertThat(candidates1).containsExactly(Pair(emptyList(), address1))
        val agent1 = registry.tryAllocate(address1, processChainId)
        assertThat(agent1).isNull()

        assertThat(inquiryCount1).isEqualTo(1)
        assertThat(allocateCount1).isEqualTo(1)
        assertThat(inquiryCount2).isEqualTo(1)
        assertThat(allocateCount2).isEqualTo(0)

        val candidates2 = registry.selectCandidates(listOf(
            SelectCandidatesParam(emptyList(), 0, 0, 1)
        ))
        assertThat(candidates2).containsExactly(Pair(emptyList(), address2))
        val agent2 = registry.tryAllocate(address2, processChainId)
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
