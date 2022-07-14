import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistry.SelectCandidatesParam
import agent.AgentRegistryFactory
import agent.RemoteAgent
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus.ERROR
import db.SubmissionRegistry.ProcessChainStatus.REGISTERED
import db.SubmissionRegistry.ProcessChainStatus.RUNNING
import db.SubmissionRegistry.ProcessChainStatus.SUCCESS
import db.SubmissionRegistryFactory
import helper.UniqueID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.AsyncMap
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for the [Scheduler]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class SchedulerTest {
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var agentRegistry: AgentRegistry
  private lateinit var agentId: String

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.findProcessChainIdsByStatus(RUNNING) } returns emptyList()
    coEvery { submissionRegistry.close() } just Runs

    // mock agent registry
    agentRegistry = mockk()
    mockkObject(AgentRegistryFactory)
    every { AgentRegistryFactory.create(any()) } returns agentRegistry

    // deploy verticle under test
    agentId = UniqueID.next()
    val options = deploymentOptionsOf(config = json {
      obj(
          ConfigConstants.AGENT_ID to agentId
      )
    })
    vertx.deployVerticle(Scheduler::class.qualifiedName, options, ctx.succeedingThenComplete())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Runs a simple test: schedules [nProcessChains] process chains and provides
   * [nAgents] mock agents to execute the process chains. Each agent needs
   * 1 second to execute a process chain. The method waits until all process
   * chains have been executed successfully.
   */
  private fun testSimple(nProcessChains: Int, nAgents: Int, vertx: Vertx, ctx: VertxTestContext) {
    val allPcs = (1..nProcessChains).map { ProcessChain() }
    testSimple(allPcs, nAgents, vertx, ctx)
  }

  /**
   * Runs a simple test: schedules [processChains] and provides [nAgents] mock
   * agents to execute the process chains. Each agent needs 1 second to execute
   * a process chain. The method waits until all process chains have been
   * executed successfully.
   */
  private fun testSimple(processChains: List<ProcessChain>, nAgents: Int, vertx: Vertx,
      ctx: VertxTestContext, verify: ((List<String>) -> Unit)? = null,
      onExecute: (suspend (String, List<String>) -> Unit)? = null,
      onAfterFetchNextProcessChain: (suspend () -> Unit)? = null) {
    val allPcs = processChains.toMutableList()
    val remainingPcs = processChains.toMutableList()
    val executedPcIds = mutableListOf<String>()

    coEvery { submissionRegistry.findProcessChainRequiredCapabilities(REGISTERED) } answers {
      remainingPcs.groupBy { it.requiredCapabilities }.map { (rc, pcs) ->
        rc to pcs.minOf { it.priority }..pcs.maxOf { it.priority }
      }
    }
    val rcsSlot = slot<Collection<String>>()
    val minPrioritySlot = slot<Int>()
    coEvery { submissionRegistry.countProcessChains(null, REGISTERED,
        capture(rcsSlot), capture(minPrioritySlot)) } answers {
      remainingPcs.filter { it.requiredCapabilities == rcsSlot.captured &&
          it.priority >= minPrioritySlot.captured }.size.toLong()
    }

    // mock agents
    val allAgents = (1..nAgents).map { n ->
      val a = mockk<Agent>()
      every { a.id } returns "Mock agent $n"
      a
    }

    val availableAgents = allAgents.toMutableList()
    val assignedAgents = mutableMapOf<String, String>()
    for (agent in allAgents) {
      val pcSlot = slot<ProcessChain>()
      coEvery { agent.execute(capture(pcSlot)) } coAnswers {
        onExecute?.invoke(pcSlot.captured.id, executedPcIds)
        delay(1000) // pretend it takes 1 second to execute the process chain
        mapOf("ARG1" to listOf("output-${pcSlot.captured.id}"))
      }
    }

    val slotSelectParams = slot<List<SelectCandidatesParam>>()
    coEvery { agentRegistry.selectCandidates(capture(slotSelectParams)) } answers {
      // all agents support all required capabilities (!) and by default, they
      // all select the required capability set with the the highest priority
      // and the most process chains
      val best = slotSelectParams.captured.maxWithOrNull(
          compareBy<SelectCandidatesParam> { it.maxPriority }
              .thenBy { it.count })
      if (best != null && availableAgents.isNotEmpty()) {
        listOf(best.requiredCapabilities to
            "${AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX}${availableAgents.first().id}")
      } else {
        emptyList()
      }
    }

    val slotAgentAddress = slot<String>()
    val slotAllocatedProcessChainId = slot<String>()
    coEvery { agentRegistry.tryAllocate(capture(slotAgentAddress),
        capture(slotAllocatedProcessChainId)) } answers {
      val capturedAgentId = slotAgentAddress.captured.removePrefix(AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX)
      val agent = availableAgents.find { it.id == capturedAgentId }
      if (agent != null) {
        availableAgents.remove(agent)
        assignedAgents[agent.id] = slotAllocatedProcessChainId.captured
        agent
      } else if (assignedAgents[capturedAgentId] == slotAllocatedProcessChainId.captured) {
        allAgents.find { it.id == capturedAgentId }
      } else {
        null
      }
    }

    val slotAgent = slot<Agent>()
    coEvery { agentRegistry.deallocate(capture(slotAgent)) } answers {
      // put back agent
      availableAgents.add(slotAgent.captured)
      assignedAgents.remove(slotAgent.captured.id)
    }

    val registerMocksForPc = { pc: ProcessChain ->
      // add running process chain to list of registered process chains again
      coEvery { submissionRegistry.setProcessChainStatus(pc.id, REGISTERED) } answers {
        ctx.verify {
          assertThat(remainingPcs).doesNotContain(pc)
        }
        remainingPcs.add(0, pc)
      }

      // register mock for start time
      coEvery { submissionRegistry.setProcessChainStartTime(pc.id, any()) } just Runs

      // register mock for results
      coEvery { submissionRegistry.setProcessChainResults(pc.id,
          mapOf("ARG1" to listOf("output-${pc.id}"))) } just Runs

      // register mocks for all successful process chains
      coEvery { submissionRegistry.setProcessChainStatus(pc.id, SUCCESS) } answers {
        ctx.verify {
          assertThat(remainingPcs).doesNotContain(pc)
        }
      }
    }

    // mock submission registry
    for (pc in allPcs) {
      registerMocksForPc(pc)
    }
    val slotAddProcessChain = slot<Collection<ProcessChain>>()
    coEvery { submissionRegistry.addProcessChains(capture(slotAddProcessChain), any()) } answers {
      for (pc in slotAddProcessChain.captured) {
        registerMocksForPc(pc)
      }
      allPcs.addAll(slotAddProcessChain.captured)
      remainingPcs.addAll(slotAddProcessChain.captured)
    }

    val slotEndTimePcId = slot<String>()
    coEvery { submissionRegistry.setProcessChainEndTime(capture(slotEndTimePcId), any()) } answers {
      // on last successful process chain ...
      executedPcIds.add(slotEndTimePcId.captured)
      if (executedPcIds.size == allPcs.size) {
        ctx.verify {
          // verify that all process chains were set to SUCCESS,
          // and that the results were set correctly
          coVerify(exactly = 1) {
            for (pc in allPcs) {
              submissionRegistry.setProcessChainResults(pc.id,
                  mapOf("ARG1" to listOf("output-${pc.id}")))
              submissionRegistry.setProcessChainStartTime(pc.id, any())
              submissionRegistry.setProcessChainEndTime(pc.id, any())
              submissionRegistry.setProcessChainStatus(pc.id, SUCCESS)
            }
          }
          verify?.invoke(executedPcIds)
        }
        ctx.completeNow()
      }
    }

    // execute process chains
    val slotFetchNextRcs = slot<Collection<String>>()
    val slotFetchMinPriority = slot<Int>()
    coEvery { submissionRegistry.fetchNextProcessChain(REGISTERED, RUNNING,
        capture(slotFetchNextRcs), capture(slotFetchMinPriority)) } coAnswers {
      val r = remainingPcs.filter {
        it.requiredCapabilities == slotFetchNextRcs.captured &&
            it.priority >= slotFetchMinPriority.captured
      }.maxByOrNull { it.priority }
      if (r != null) {
        remainingPcs.remove(r)
      }
      onAfterFetchNextProcessChain?.invoke()
      r
    }
    coEvery { submissionRegistry.existsProcessChain(REGISTERED, any()) } answers {
      remainingPcs.isNotEmpty() }

    vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)
  }

  @Test
  fun oneChainOneAgent(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(1, 1, vertx, ctx)
  }

  @Test
  fun twoChainsOneAgent(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(2, 1, vertx, ctx)
  }

  @Test
  fun twoChainsTwoAgents(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(2, 2, vertx, ctx)
  }

  @Test
  fun twentyChainsTenAgents(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(20, 10, vertx, ctx)
  }

  @Test
  fun twoChainsTwoAgentsDifferentRequiredCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(listOf(
        ProcessChain(requiredCapabilities = setOf("docker")),
        ProcessChain()
    ), 2, vertx, ctx)
  }

  @Test
  fun priorities(vertx: Vertx, ctx: VertxTestContext) {
    val pc1 = ProcessChain(id = "1", priority = 1)
    val pc2 = ProcessChain(id = "2", priority = 1)
    val pc3 = ProcessChain(id = "3", priority = 2)
    testSimple(listOf(pc1, pc2, pc3), 1, vertx, ctx, verify = { executedPcs ->
      assertThat(executedPcs).hasSize(3)
      assertThat(executedPcs.first()).isEqualTo(pc3.id)
    })
  }

  @Test
  fun prioritiesDifferentRcs(vertx: Vertx, ctx: VertxTestContext) {
    val pc1 = ProcessChain(id = "1", priority = 1, requiredCapabilities = setOf("docker"))
    val pc2 = ProcessChain(id = "2", priority = 1, requiredCapabilities = setOf("docker"))
    val pc3 = ProcessChain(id = "3", priority = 2, requiredCapabilities = setOf("sleep"))
    testSimple(listOf(pc1, pc2, pc3), 1, vertx, ctx, verify = { executedPcs ->
      assertThat(executedPcs).hasSize(3)
      assertThat(executedPcs.first()).isEqualTo(pc3.id)
    })
  }

  @Test
  fun prioritiesDifferentRcsMoreComplex(vertx: Vertx, ctx: VertxTestContext) {
    val pcA1 = ProcessChain(id = "A1", priority = 1, requiredCapabilities = setOf("docker"))
    val pcA2 = ProcessChain(id = "A2", priority = 1, requiredCapabilities = setOf("docker"))
    val pcA3 = ProcessChain(id = "A3", priority = 10, requiredCapabilities = setOf("docker"))
    val pcA4 = ProcessChain(id = "A4", priority = 11, requiredCapabilities = setOf("docker"))
    val pcA5 = ProcessChain(id = "A5", priority = 12, requiredCapabilities = setOf("docker"))

    val pcB1 = ProcessChain(id = "B1", priority = 8, requiredCapabilities = setOf("sleep"))
    val pcB2 = ProcessChain(id = "B2", priority = 8, requiredCapabilities = setOf("sleep"))
    val pcB3 = ProcessChain(id = "B3", priority = 10, requiredCapabilities = setOf("sleep"))

    testSimple(listOf(pcA1, pcA2, pcA3, pcA4, pcA5, pcB1, pcB2, pcB3),
        1, vertx, ctx, verify = { executedPcs ->
      assertThat(executedPcs).containsExactly("A5", "A4", "A3", "B3", "B1", "B2", "A1", "A2")
    })
  }

  @Test
  fun prioritiesAddProcessChainWhileRunning(vertx: Vertx, ctx: VertxTestContext) {
    val pc1 = ProcessChain(id = "1", priority = 1)
    val pc2 = ProcessChain(id = "2", priority = 1)
    val pc3 = ProcessChain(id = "3", priority = 2)
    testSimple(listOf(pc1, pc2), 1, vertx, ctx, onExecute = { _, executedPcs ->
      if (executedPcs.isEmpty()) {
        // add pc3 while either pc1 or pc2 is currently running
        // pc3 should then be scheduled at second position because it as
        // a higher priority than the remaining pc
        submissionRegistry.addProcessChains(listOf(pc3), UniqueID.next())
      }
    }, verify = { executedPcs ->
      assertThat(executedPcs).hasSize(3)
      assertThat(executedPcs[1]).isEqualTo(pc3.id)
    })
  }

  @Test
  fun prioritiesAddProcessChainWithDifferentRcsWhileRunning(vertx: Vertx, ctx: VertxTestContext) {
    val pc1 = ProcessChain(id = "1", priority = 1, requiredCapabilities = setOf("docker"))
    val pc2 = ProcessChain(id = "2", priority = 1, requiredCapabilities = setOf("docker"))
    val pc3 = ProcessChain(id = "3", priority = 2, requiredCapabilities = setOf("sleep"))
    testSimple(listOf(pc1, pc2), 1, vertx, ctx, onExecute = { _, executedPcs ->
      if (executedPcs.isEmpty()) {
        // add pc3 while either pc1 or pc2 is currently running
        // pc3 should then be scheduled at second position because it as
        // a higher priority than the remaining pc
        submissionRegistry.addProcessChains(listOf(pc3), UniqueID.next())
      }
    }, verify = { executedPcs ->
      assertThat(executedPcs).hasSize(3)
      assertThat(executedPcs[1]).isEqualTo(pc3.id)
    })
  }

  @Test
  fun deallocateAgentOnError(vertx: Vertx, ctx: VertxTestContext) {
    val message = "THIS is an ERROR"

    // mock agent
    val agent = mockk<Agent>()
    val agentId = "Mock agent"
    val pc = ProcessChain()
    every { agent.id } returns agentId
    coEvery { agent.execute(any()) } throws Exception(message)
    coEvery { agentRegistry.tryAllocate("${AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX}$agentId",
        pc.id) } returns agent andThen null
    coEvery { agentRegistry.selectCandidates(any()) } returns listOf(Pair(emptySet(),
        "${AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX}$agentId")) andThen emptyList()

    // mock submission registry
    coEvery { submissionRegistry.setProcessChainStatus(pc.id, ERROR) } just Runs
    coEvery { submissionRegistry.setProcessChainStartTime(pc.id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainEndTime(pc.id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainErrorMessage(pc.id, message) } just Runs
    coEvery { submissionRegistry.findProcessChainRequiredCapabilities(REGISTERED) } returns
        listOf(emptySet<String>() to 0..0)
    coEvery { submissionRegistry.countProcessChains(null, REGISTERED, emptySet(), 0) } returns 13L
    coEvery { submissionRegistry.existsProcessChain(REGISTERED, emptySet()) } returns true

    coEvery { agentRegistry.deallocate(agent) } answers {
      ctx.verify {
        coVerify(exactly = 1) {
          submissionRegistry.setProcessChainStatus(pc.id, ERROR)
          submissionRegistry.setProcessChainErrorMessage(pc.id, message)
        }
      }
      ctx.completeNow()
    }

    // execute process chains
    coEvery { submissionRegistry.fetchNextProcessChain(REGISTERED, RUNNING, any(), 0) } returns
        pc andThen null

    vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)
  }

  /**
   * Test if process chains that are not monitored by a scheduler instance but
   * are still executed by an agent can be resumed.
   */
  @Test
  fun resumeProcessChains(vertx: Vertx, ctx: VertxTestContext) {
    // a process chain that is not executed by an agent (should be restarted)
    val pc1 = ProcessChain(id = "pc1")
    // a process chain that is executed by an agent (should be resumed!)
    val pc2 = ProcessChain(id = "pc2")
    // a process chain that is still monitored by another scheduler
    val pc3 = ProcessChain(id = "pc3")
    // a process chain that was first running but is then not anymore
    val pc4 = ProcessChain(id = "pc4")

    val pc2Results = mapOf("var" to listOf("test.txt"))

    CoroutineScope(vertx.dispatcher()).launch {
      // add another scheduler that monitors 'pc3'
      val otherSchedulerId = UniqueID.next()
      val sharedData = vertx.sharedData()
      val schedulersPromise = Promise.promise<AsyncMap<String, Boolean>>()
      sharedData.getAsyncMap("Scheduler.Async", schedulersPromise)
      val schedulers = schedulersPromise.future().await()
      schedulers.put(otherSchedulerId, true).await()
      var otherSchedulerCalled = false
      val schedulerRunningAddress = AddressConstants.SCHEDULER_PREFIX +
          "$otherSchedulerId${AddressConstants.SCHEDULER_RUNNING_PROCESS_CHAINS_SUFFIX}"
      vertx.eventBus().consumer<Any?>(schedulerRunningAddress) { msg ->
        otherSchedulerCalled = true
        msg.reply(json {
          array(pc3.id)
        })
      }

      // mock submission registry
      coEvery { submissionRegistry.findProcessChainIdsByStatus(RUNNING) } returns
          listOf(pc1.id, pc2.id, pc3.id, pc4.id) andThen listOf(pc1.id, pc2.id, pc3.id)
      coEvery { submissionRegistry.setProcessChainStatus(pc1.id, REGISTERED) } just Runs
      coEvery { submissionRegistry.setProcessChainStartTime(pc1.id, null) } just Runs
      coEvery { submissionRegistry.findProcessChainById(pc2.id) } returns pc2
      coEvery { submissionRegistry.setProcessChainResults(pc2.id, pc2Results) } just Runs
      coEvery { submissionRegistry.setProcessChainStatus(pc2.id, SUCCESS) } just Runs
      coEvery { submissionRegistry.setProcessChainEndTime(pc2.id, any()) } just Runs
      coEvery { submissionRegistry.existsProcessChain(REGISTERED, any()) } returns false
      coEvery { submissionRegistry.findProcessChainRequiredCapabilities(REGISTERED) } returns
          listOf(emptySet<String>() to 0..0)
      coEvery { submissionRegistry.countProcessChains(null, REGISTERED, emptySet(), 0) } returns 4L

      // mock agent registry
      coEvery { agentRegistry.getAgentIds() } returns setOf(agentId)
      val agentAddress = "${AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX}$agentId"
      vertx.eventBus().consumer<JsonObject>(agentAddress) { msg ->
        ctx.verify {
          assertThat(msg.body().getString("action")).isEqualTo("info")
        }
        msg.reply(json {
          obj(
              "id" to agentId,
              "processChainId" to pc2.id
          )
        })
      }
      val mockAgent = mockk<RemoteAgent>()
      coEvery { agentRegistry.tryAllocate(agentAddress, pc2.id) } returns mockAgent
      every { mockAgent.id } returns agentId
      coEvery { mockAgent.execute(pc2) } returns pc2Results
      coEvery { agentRegistry.deallocate(mockAgent) } just Runs

      // finalize (agentRegistry.selectCandidates should be called at then end
      // when pc2 has been resumed and executed successfully)
      coEvery { agentRegistry.selectCandidates(any()) } answers {
        ctx.verify {
          assertThat(otherSchedulerCalled).isTrue

          coVerify(atLeast = 2) {
            submissionRegistry.findProcessChainIdsByStatus(RUNNING)
          }
          coVerify(exactly = 1) {
            // check that pc1 was successfully reset
            submissionRegistry.setProcessChainStatus(pc1.id, REGISTERED)
            submissionRegistry.setProcessChainStartTime(pc1.id, null)

            // check that pc2 was successfully resumed
            submissionRegistry.findProcessChainById(pc2.id)
            submissionRegistry.setProcessChainResults(pc2.id, pc2Results)
            submissionRegistry.setProcessChainStatus(pc2.id, SUCCESS)
            submissionRegistry.setProcessChainEndTime(pc2.id, any())
          }
          coVerify(exactly = 1) {
            // check that pc2 was successfully executed
            agentRegistry.getAgentIds()
            agentRegistry.tryAllocate(agentAddress, pc2.id)
            mockAgent.execute(pc2)
            agentRegistry.deallocate(mockAgent)
          }
        }

        ctx.completeNow()

        emptyList()
      }

      vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_ORPHANS_NOW, null)
    }
  }

  /**
   * Test that, if we call `lookupOrphans` while a lookup operation is running,
   * no process chain is scheduled twice
   */
  @Test
  fun concurrentLookupAndOrphanLookup(vertx: Vertx, ctx: VertxTestContext) {
    val pc = ProcessChain()

    val mockAgentId = "Mock agent 1"
    val agentAddress = "${AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX}$mockAgentId"

    testSimple(listOf(pc), 1, vertx, ctx, onAfterFetchNextProcessChain = {
      // pretend that the process chain is now running
      coEvery { submissionRegistry.findProcessChainIdsByStatus(RUNNING) } returns listOf(pc.id)
      coEvery { submissionRegistry.findProcessChainById(pc.id) } returns pc

      // mock agent and pretend that it is executing the process chain
      coEvery { agentRegistry.getAgentIds() } returns setOf(mockAgentId)
      vertx.eventBus().consumer<JsonObject>(agentAddress) { msg ->
        ctx.verify {
          assertThat(msg.body().getString("action")).isEqualTo("info")
        }

        // If we get here, it means that lookupOrphans has actually found
        // our process chain and considers it an orphan!! This should not happen!
        // The scheduler has just fetched it from the database and did not have
        // a chance yet to schedule it.
        ctx.failNow("Process chain is considered an orphan!")

        msg.reply(json {
          obj(
              "id" to mockAgentId,
              "processChainId" to pc.id
          )
        })
      }

      // force lookup for orphans now
      vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_ORPHANS_NOW, null)

      // lookupOrphans should definitely be executed before fetchNextProcessChain returns
      delay(1000)
    }, verify = {
      coVerify(exactly = 1) {
        // The process chain should have been allocated only once!
        // (Actually, the following line should never throw. If you get an
        // error here, it means the unit test is broken. The `ctx.fail()` call
        // in the consumer above should have already stopped the test!)
        agentRegistry.tryAllocate(agentAddress, pc.id)
      }
    })
  }
}
