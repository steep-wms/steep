import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistry.ProcessChainStatus.ERROR
import db.SubmissionRegistry.ProcessChainStatus.REGISTERED
import db.SubmissionRegistry.ProcessChainStatus.RUNNING
import db.SubmissionRegistry.ProcessChainStatus.SUCCESS
import db.SubmissionRegistryFactory
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.delay
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

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext) {
    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create() } returns submissionRegistry

    // mock agent registry
    agentRegistry = mockk()
    mockkObject(AgentRegistryFactory)
    every { AgentRegistryFactory.create() } returns agentRegistry

    // deploy verticle under test
    vertx.deployVerticle(Scheduler::class.qualifiedName, ctx.completing())
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  /**
   * Runs a simple test: schedules `nProcessChains` process chains and provides
   * `nAgents` mock agents to execute the process chains. Each agent needs
   * 1 second to execute a process chain. The method waits until all process
   * chains have been executed successfully.
   * @param nProcessChains the number of process chains to schedule
   * @param nAgents the number of mock agents to create
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  private fun testSimple(nProcessChains: Int, nAgents: Int, vertx: Vertx, ctx: VertxTestContext) {
    // mock agents
    val allAgents = (1..nAgents).map { n ->
      val a = mockk<Agent>()
      every { a.id } returns "Mock agent $n"
      a
    }

    val availableAgents = allAgents.toMutableList()
    for (agent in allAgents) {
      val pcSlot = slot<ProcessChain>()
      coEvery { agent.execute(capture(pcSlot)) } coAnswers {
        delay(1000) // pretend it takes 1 second to execute the process chain
        mapOf("ARG1" to listOf("output-${pcSlot.captured.id}"))
      }
    }

    coEvery { agentRegistry.allocate(any()) } answers {
      // allocate first agent from list of available agents
      if (availableAgents.isEmpty()) {
        null
      } else {
        availableAgents.removeAt(0)
      }
    }

    val slotAgent = slot<Agent>()
    coEvery { agentRegistry.deallocate(capture(slotAgent)) } answers {
      // put back agent
      availableAgents.add(slotAgent.captured)
    }

    // mock submission registry
    val allPcs = (1..nProcessChains).map { ProcessChain() }
    val registeredPcs = allPcs.toMutableList()
    for (pc in allPcs) {
      // remove running process chains from list of registered process chains
      coEvery { submissionRegistry.setProcessChainStatus(pc.id, RUNNING) } answers {
        ctx.verify {
          assertThat(registeredPcs).contains(pc)
        }
        registeredPcs.remove(pc)
      }

      // register mock for output
      coEvery { submissionRegistry.setProcessChainOutput(pc.id,
          mapOf("ARG1" to listOf("output-${pc.id}"))) } just Runs
    }

    for (pc in allPcs.dropLast(1)) {
      // register mocks for all successful process chains
      coEvery { submissionRegistry.setProcessChainStatus(pc.id, SUCCESS) } answers {
        ctx.verify {
          assertThat(registeredPcs).doesNotContain(pc)
        }
      }
    }

    coEvery { submissionRegistry.setProcessChainStatus(allPcs.last().id, SUCCESS) } answers {
      // on last successful process chain ...
      ctx.verify {
        assertThat(registeredPcs).doesNotContain(allPcs.last())

        // verify that all process chains were set to RUNNING and then SUCCESS,
        // and that the output was set correctly
        coVerify(exactly = 1) {
          for (pc in allPcs) {
            submissionRegistry.setProcessChainStatus(pc.id, RUNNING)
            submissionRegistry.setProcessChainOutput(pc.id,
                mapOf("ARG1" to listOf("output-${pc.id}")))
            submissionRegistry.setProcessChainStatus(pc.id, SUCCESS)
          }
        }
      }
      ctx.completeNow()
    }

    // execute process chains
    coEvery { submissionRegistry.findProcessChainsByStatus(REGISTERED, 1) } answers {
      registeredPcs.take(1)
    }

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
  fun deallocateAgentOnError(vertx: Vertx, ctx: VertxTestContext) {
    // mock agent
    val agent = mockk<Agent>()
    every { agent.id } returns "Mock agent"
    coEvery { agent.execute(any()) } throws Exception()
    coEvery { agentRegistry.allocate(any()) } returns agent
    coEvery { agentRegistry.deallocate(agent) } just Runs

    // mock submission registry
    val pc = ProcessChain()
    coEvery { submissionRegistry.setProcessChainStatus(pc.id, RUNNING) } just Runs
    coEvery { submissionRegistry.setProcessChainStatus(pc.id, ERROR) } just Runs

    coEvery { agentRegistry.deallocate(agent) } answers {
      ctx.verify {
        coVerify(exactly = 1) {
          submissionRegistry.setProcessChainStatus(pc.id, RUNNING)
          submissionRegistry.setProcessChainStatus(pc.id, ERROR)
        }
      }
      ctx.completeNow()
    }

    // execute process chains
    coEvery { submissionRegistry.findProcessChainsByStatus(REGISTERED, 1) } returns
        listOf(pc) andThen emptyList()

    vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)
  }
}
