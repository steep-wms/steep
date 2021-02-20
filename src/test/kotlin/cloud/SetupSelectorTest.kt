package cloud

import coVerify
import db.VMRegistry
import io.mockk.coEvery
import io.mockk.mockk
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.cloud.PoolAgentParams
import model.cloud.VM
import model.setup.Setup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [SetupSelector]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class SetupSelectorTest {
  companion object {
    private const val RQ1 = "a"
    private const val RQ2 = "b"
    private val SETUP01 = Setup("setup1", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 1, providedCapabilities = listOf(RQ1))
    private val SETUP02 = Setup("setup2", "myOtherFlavor", "myOtherImage", "az-02",
        100000, maxVMs = 1, providedCapabilities = listOf(RQ2))
    private val SETUP03 = Setup("setup3", "myFlavor", "myImage", "az-03",
        300000, maxVMs = 1, providedCapabilities = listOf(RQ1))
    private val SETUP04 = Setup("setup4", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 4, providedCapabilities = listOf(RQ1))
    private val SETUP05 = Setup("setup5", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 4, providedCapabilities = listOf(RQ1))
    private val SETUP06 = Setup("setup6", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 4, maxCreateConcurrent = 3, providedCapabilities = listOf(RQ1))
    private val SETUP07 = Setup("setup7", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 5, maxCreateConcurrent = 5, providedCapabilities = listOf(RQ1))
    private val SETUP08= Setup("setup7", "myFlavor", "myImage", "az-01",
        500000, maxVMs = 5, maxCreateConcurrent = 5, providedCapabilities = listOf(RQ1, RQ2))
  }

  /**
   * Select setups by required capabilities
   */
  @Test
  fun selectByCapabilities(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(any()) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(any()) } returns 0

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, emptyList(), emptyList())).isEmpty()
        assertThat(selector.select(1, listOf(RQ1), listOf(SETUP01, SETUP02)))
            .containsExactly(SETUP01)
        assertThat(selector.select(1, listOf(RQ2), listOf(SETUP01, SETUP02)))
            .containsExactly(SETUP02)
        assertThat(selector.select(1, listOf(RQ1, RQ2), listOf(SETUP01, SETUP02)))
            .isEmpty()
        assertThat(selector.select(1, listOf(RQ1), listOf(SETUP01, SETUP02, SETUP03)))
            .containsExactly(SETUP01)
        assertThat(selector.select(2, listOf(RQ1), listOf(SETUP01, SETUP02, SETUP03)))
            .containsExactly(SETUP01, SETUP03)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if there already is an existing VM
   */
  @Test
  fun dontSelectExistingVM(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(any()) } returns 0

    val setups = listOf(SETUP01, SETUP02, SETUP03)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .containsExactly(SETUP03)
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP03)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if a matching VM is currently starting
   */
  @Test
  fun dontSelectStartingVM(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP03.id) } returns 0

    val setups = listOf(SETUP01, SETUP02, SETUP03)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP03)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if a matching VM already exists or is currently starting
   */
  @Test
  fun dontSelectMixed(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP04.id) } returns 2
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP05.id) } returns 2
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP01.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP04.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP05.id) } returns 0

    val setups = listOf(SETUP01, SETUP02, SETUP03, SETUP04, SETUP05)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP04)
        assertThat(selector.select(3, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP05)
        assertThat(selector.select(4, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP05)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if `maxConcurrent` is exceeded
   */
  @Test
  fun maxConcurrent(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, emptyList())

    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP01.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP04.id) } returns 2
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP05.id) } returns 2
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP06.id) } returns 2
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP01.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP03.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP04.id) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP05.id) } returns 1
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP06.id) } returns 1

    val setups = listOf(SETUP01, SETUP02, SETUP03, SETUP04, SETUP05, SETUP06)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(3, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(4, listOf(RQ1), setups))
            .containsExactly(SETUP04)
        assertThat(selector.select(5, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP06)
        assertThat(selector.select(6, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP06, SETUP06)
        assertThat(selector.select(10, listOf(RQ1), setups))
            .containsExactly(SETUP04, SETUP06, SETUP06)
      }
      ctx.completeNow()
    }
  }

  /**
   * Do not return a setup if the maximum number of agents is exceeded
   */
  @Test
  fun maxAgents(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(PoolAgentParams(listOf(RQ1), max = 4)))

    coEvery { vmRegistry.findNonTerminatedVMs() } returns listOf(VM(setup = SETUP07), VM(setup = SETUP07))
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(SETUP07.id) } returns 2
    coEvery { vmRegistry.countStartingVMsBySetup(SETUP07.id) } returns 1

    val setups = listOf(SETUP07)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(1, listOf(RQ1), setups))
            .isEmpty()
        assertThat(selector.select(2, listOf(RQ1), setups))
            .containsExactly(SETUP07)
        assertThat(selector.select(3, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07)
        assertThat(selector.select(4, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07)
        assertThat(selector.select(10, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07)
      }
      ctx.completeNow()
    }
  }

  /**
   * Configure two capability sets ([RQ1] and [RQ1] + [RQ2]) in the agent pool
   * and then check if the correct number of setups is returned. The first set
   * has a maximum of 3 and the second a maximum of 1. If we request 5 setups
   * for [RQ1], only 3 should be returned (3 with [RQ1] and none with [RQ2])
   */
  @Test
  fun maxAgentsTwoReqCapSets(vertx: Vertx, ctx: VertxTestContext) {
    val vmRegistry = mockk<VMRegistry>()
    val selector = SetupSelector(vmRegistry, listOf(
        PoolAgentParams(listOf(RQ1), max = 3), PoolAgentParams(listOf(RQ1, RQ2), max = 1)))

    coEvery { vmRegistry.findNonTerminatedVMs() } returns emptyList()
    coEvery { vmRegistry.countNonTerminatedVMsBySetup(any()) } returns 0
    coEvery { vmRegistry.countStartingVMsBySetup(any()) } returns 0

    val setups = listOf(SETUP07, SETUP08)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        assertThat(selector.select(5, listOf(RQ1), setups))
            .containsExactly(SETUP07, SETUP07, SETUP07)
      }
      ctx.completeNow()
    }
  }
}
