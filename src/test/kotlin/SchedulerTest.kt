import agent.Agent
import agent.AgentRegistry
import agent.AgentRegistryFactory
import db.RuleRegistry
import db.RuleRegistryFactory
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
import model.rules.Rule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for the [Scheduler]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class SchedulerTest {
  companion object {
    private const val WITH_RULES = "withRules"
  }

  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var agentRegistry: AgentRegistry

  @BeforeEach
  fun setUp(vertx: Vertx, ctx: VertxTestContext, info: TestInfo) {
    // mock submission registry
    submissionRegistry = mockk()
    mockkObject(SubmissionRegistryFactory)
    every { SubmissionRegistryFactory.create(any()) } returns submissionRegistry
    coEvery { submissionRegistry.close() } just Runs

    // mock rule registry
    val ruleRegistry: RuleRegistry = mockk()
    mockkObject(RuleRegistryFactory)
    every { RuleRegistryFactory.create(any()) } returns ruleRegistry
    if (info.tags.contains(WITH_RULES)) {
      coEvery { ruleRegistry.findRules() } returns listOf(Rule(
          name = "Modify results",
          target = Rule.Target.PROCESSCHAIN_RESULTS,
          condition = "(results, pc) => true",
          action = """
              function(results, pc) {
                Object.keys(results).forEach(id => {
                  results[id] = results[id].flatMap(r => [r, r + ".bak"]);
                });
              }
            """.trimIndent()
      ))
    } else {
      coEvery { ruleRegistry.findRules() } returns emptyList()
    }

    // mock agent registry
    agentRegistry = mockk()
    mockkObject(AgentRegistryFactory)
    every { AgentRegistryFactory.create(any()) } returns agentRegistry

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
  private fun testSimple(nProcessChains: Int, nAgents: Int, vertx: Vertx,
      ctx: VertxTestContext, withRules: Boolean = false) {
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
      // add running process chain to list of registered process chains again
      coEvery { submissionRegistry.setProcessChainStatus(pc.id, REGISTERED) } answers {
        ctx.verify {
          assertThat(registeredPcs).doesNotContain(pc)
        }
        registeredPcs.add(0, pc)
      }

      // register mock for start and end time
      coEvery { submissionRegistry.setProcessChainStartTime(pc.id, any()) } just Runs
      coEvery { submissionRegistry.setProcessChainEndTime(pc.id, any()) } just Runs

      // register mock for results
      if (withRules) {
        coEvery { submissionRegistry.setProcessChainResults(pc.id,
            mapOf("ARG1" to listOf("output-${pc.id}", "output-${pc.id}.bak"))) } just Runs
      } else {
        coEvery { submissionRegistry.setProcessChainResults(pc.id,
            mapOf("ARG1" to listOf("output-${pc.id}"))) } just Runs
      }
    }

    for (pc in allPcs) {
      // register mocks for all successful process chains
      coEvery { submissionRegistry.setProcessChainStatus(pc.id, SUCCESS) } answers {
        ctx.verify {
          assertThat(registeredPcs).doesNotContain(pc)
        }
      }
    }

    coEvery { submissionRegistry.setProcessChainEndTime(allPcs.last().id, any()) } answers {
      // on last successful process chain ...
      ctx.verify {
        assertThat(registeredPcs).doesNotContain(allPcs.last())

        // verify that all process chains were set to SUCCESS,
        // and that the results were set correctly
        coVerify(exactly = 1) {
          for (pc in allPcs) {
            if (withRules) {
              submissionRegistry.setProcessChainResults(pc.id,
                  mapOf("ARG1" to listOf("output-${pc.id}", "output-${pc.id}.bak")))
            } else {
              submissionRegistry.setProcessChainResults(pc.id,
                  mapOf("ARG1" to listOf("output-${pc.id}")))
            }
            submissionRegistry.setProcessChainStartTime(pc.id, any())
            submissionRegistry.setProcessChainEndTime(pc.id, any())
            submissionRegistry.setProcessChainStatus(pc.id, SUCCESS)
          }
        }
      }
      ctx.completeNow()
    }

    // execute process chains
    coEvery { submissionRegistry.fetchNextProcessChain(REGISTERED, RUNNING) } answers {
      if (registeredPcs.isEmpty()) null else registeredPcs.removeAt(0)
    }

    vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)
  }

  @Test
  fun oneChainOneAgent(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(1, 1, vertx, ctx)
  }

  @Test
  @Tag(WITH_RULES)
  fun oneChainOneAgentWithRule(vertx: Vertx, ctx: VertxTestContext) {
    testSimple(1, 1, vertx, ctx, true)
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
    val message = "THIS is an ERROR"

    // mock agent
    val agent = mockk<Agent>()
    every { agent.id } returns "Mock agent"
    coEvery { agent.execute(any()) } throws Exception(message)
    coEvery { agentRegistry.allocate(any()) } returns agent
    coEvery { agentRegistry.deallocate(agent) } just Runs

    // mock submission registry
    val pc = ProcessChain()
    coEvery { submissionRegistry.setProcessChainStatus(pc.id, ERROR) } just Runs
    coEvery { submissionRegistry.setProcessChainStartTime(pc.id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainEndTime(pc.id, any()) } just Runs
    coEvery { submissionRegistry.setProcessChainErrorMessage(pc.id, message) } just Runs

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
    coEvery { submissionRegistry.fetchNextProcessChain(REGISTERED, RUNNING) } returns
        pc andThen null

    vertx.eventBus().publish(AddressConstants.SCHEDULER_LOOKUP_NOW, null)
  }
}
