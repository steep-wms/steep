import TestMetadata.services
import db.PluginRegistry
import db.PluginRegistryFactory
import helper.ConsecutiveID
import helper.WorkflowValidator
import helper.YamlUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import model.macro.Macro
import model.plugins.OutputAdapterPlugin
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.ExecuteAction
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * Tests for [ProcessChainGenerator]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class ProcessChainGeneratorTest {
  companion object {
    private data class T(val workflowName: String, val resultsName: String = workflowName)

    private lateinit var macros: Map<String, Macro>

    private val tests = listOf(
        // Test if a simple workflow with a single service can be converted
        // correctly
        T("singleService"),

        // Test if a simple workflow with a two independent services is
        // converted to two process chains
        T("twoIndependentServices"),

        // Test if a simple workflow with a two dependent services is converted
        // to a single process chain
        T("twoDependentServices"),

        // Test if a simple workflow with a two dependent services is converted
        // to a single process chain even if the services are in reverse order
        // in the workflow
        T("twoDependentServicesReverse", "twoDependentServices"),

        // Test if a simple workflow with a three dependent services is
        // converted to a single process chain
        T("threeDependentServices"),

        // Test if a simple workflow with four services is converted to two
        // process chains
        T("twoProcessChains"),

        // Test if a simple workflow with a four services is converted to two
        // process chains even if the results of the first process chain arrive
        // earlier than those of the second
        T("twoProcessChains", "twoProcessChainsAsyncResults"),

        // Test if a workflow with a service that joins the results of two
        // preceding services can be converted
        T("join"),

        // Test if a workflow with a service that joins the results of two
        // preceding services. Test if the process chains are generated
        // correctly even if the results of the first service arrive earlier
        // than those of the second.
        T("join", "joinAsyncResults"),

        // Test if a simple workflow with a two independent services that read
        // the same file is converted to two process chains
        T("twoIndependentServicesSameFile"),

        // Test a workflow with two services that depend on the results of one
        // preceding service
        T("fork"),

        // Test a workflow with a service that produces two outputs and another
        // one depending on both these outputs
        T("twoOutputs"),

        // Test a workflow with a service that produces two outputs and two
        // subsequent services each of them depending on one of these outputs
        T("twoOutputsTwoServices"),

        // Test a workflow with a fork and a join
        T("diamond"),

        // Test a small graph
        T("smallGraph"),

        // Test if a missing parameter with a default value is correctly added
        // as an argument
        T("missingDefaultParameter"),

        // Test if a service requiring Docker is converted correctly
        T("serviceWithDocker"),

        // Test if a service requiring runtime arguments is converted correctly
        T("serviceWithRuntimeArgs"),

        // Test if a for-each action can be unrolled correctly
        T("forEach"),

        // Test if a for-each action with a pre-defined list of inputs can be
        // unrolled correctly
        T("forEachPredefinedList"),

        // Test if a for-each action can iterate over a predefined list of lists
        // and an inner join action can access the individual lists
        T("forEachPredefinedListOfLists"),

        // Test if a for-each action with a pre-defined list of inputs and two
        // sub-actions can be unrolled correctly
        T("forEachPredefinedListTwoServices"),

        // Test if a for-each action with a pre-defined list of inputs and two
        // sub-actions can be unrolled correctly even if the sub-actions are
        // in reverse order
        T("forEachPredefinedListTwoServicesReverse", "forEachPredefinedListTwoServices"),

        // Test if nested for-each actions can be unrolled correctly
        T("forEachNested"),

        // Test if a subsequent for-each action can iterate over a list of
        // lists generated by an earlier embedded for-each action and it its
        // inner join action can access the individual lists
        T("forEachNestedListOfLists"),

        // Test if nested for-each actions that iterate over pre-defined lists
        // can be unrolled correctly (and in one step)
        T("forEachNestedPredefinedList"),

        // Test if nested for-each actions that iterate over pre-defined lists
        // can be unrolled correctly if the inner for-each action contains an
        // action that depends on the results of an action from the outer
        // for-each action
        T("forEachNestedPredefinedDependent"),

        // Test if a nested for-each action that depends on the enumerator of
        // the outer for-each action and whose actions depend on the results of
        // actions from the outer for-each action can be unrolled (in other
        // words: test if a complex situation with nested for-each actions can
        // be handled)
        T("forEachNestedComplex"),

        // Test for-each action with yield
        T("forEachYield"),

        // Test if a for-each action with yieldToOutput can yield to a
        // subsequent for-each action early, meaning the second for-each
        // action can already start although the first one has not completely
        // finished yet
        T("forEachYield", "forEachYieldEarly"),

        // Test for-each action with yield and subsequent join
        T("forEachYieldJoin"),

        // Test if a subsequent action can receive a list of files as directory
        T("directoryInput"),

        // Test if a subsequent action in a separate process chain can receive
        // a list of files as directory
        T("directoryInputProcessChains"),

        // Test that the results of a nested for action can be merged to a
        // subsequent join
        T("forEachYieldForEach"),

        // Test if output variables can have prefixes defined
        T("outputPrefix"),

        // Test if we can yield an output of a sub-action back to the for-each
        // action's input
        T("forEachYieldToInput"),

        // Test if more sub-actions are already generated from a recursive
        // for-each action if the first results arrive early
        T("forEachYieldToInput", "forEachYieldToInputEarly"),

        // Test if a recursive for-each action can contain another for-each
        // action
        T("forEachYieldToInputNested"),

        // Test if yield to input is also working in inner loops
        T("forEachYieldToInputNestedDeep"),

        // Test if a recursive for-each action can have sub-actions that
        // lead to three process chains, one of which contains another
        // for-each action
        T("forEachYieldToInputNestedDelayed"),

        // Test if a join action can follow a recursive for-each action
        T("forEachYieldToInputJoin"),

        // Test if a recursive for-each action and an execute action can have
        // the same input
        T("forEachYieldToInputSameInput"),

        // Test if a recursive for-each action and an execute action can have
        // the same input, even if the execute action depends on two other
        // actions and will therefore only be executed during the second
        // iteration of [ProcessChainGenerator.generate]
        T("forEachYieldToInputSameInputDeferred"),

        // Same as `forEachYieldToInputSameInputDeferred` but with a predefined
        // list of inputs
        T("forEachYieldToInputSameInputDeferredPredefined"),

        // Test if a recursive for-each action and an execute action can have
        // the same input, even if the execute action depends on two other
        // actions that in turn also depend on an action, so that the execute
        // action will therefore only be executed during the third iteration
        // of [ProcessChainGenerator.generate] (i.e. when the input should have
        // definitely be forged by the recursive for-each action)
        T("forEachYieldToInputSameInputDeferredMore"),

        // Same as `forEachYieldToInputSameInputDeferredMore` but with a
        // predefined list of inputs
        T("forEachYieldToInputSameInputDeferredMorePredefined"),

        // Test if a store flag for a single service is evaluated correctly
        T("store"),

        // Test if a store flag for two dependent services is evaluated
        // correctly
        T("storeTwoDependent"),

        // Test if a store flag for two output variables of three dependent
        // services is evaluated correctly
        T("storeThreeDependent"),

        // Test if a store flag inside a forEach action is evaluated correctly
        T("storeForEach"),

        // Test that a retry policy from service metadata is correctly applied
        T("retryService"),

        // Test that a retry policy from an executable action is applied
        T("retryAction"),

        // Test that a retry policy from an executable action overrides the
        // one specified in the service metadata
        T("retryActionServiceOverride"),

        // Test if an action can have an id and that ID is forwarded to the executable
        T("actionId"),

        // Test if we can specify a dependency between two services via
        // `dependsOn` even if they are not connected through outputs and inputs
        T("dependsOn"),

        // Test if we can specify a dependency between two actions via
        // `dependsOn` even if the actions are in reverse order in the workflow
        T("dependsOnReverse", "dependsOn"),

        // Test if an action can depend on two other independent actions
        T("dependsOnJoin"),

        // Test if two independent actions can depend on an action
        T("dependsOnSplit"),

        // Test if we can build a chain of three actions via `dependsOn`
        T("dependsOnChain"),

        // Test if we can build a single process chain of three actions A, B,
        // and C via `dependsOn` where the last one (C) is a join action that
        // uses the outputs of the previous two actions (A and B) as inputs.
        // Normally, this would mean that we get three process chains because
        // A and B may run in parallel, but if all actions are connected
        // through `dependsOn`, we should get only one process chain because
        // they need to be executed in sequence anyhow.
        T("dependsOnChainJoin"),

        // Test if an action inside a for-each action can depend on another
        // action inside the same for-each action
        T("dependsOnInsideForEach"),

        // Test if an action inside a for-each action can depend on another
        // action inside the same for-each action even if the actions are in
        // reverse order
        T("dependsOnInsideForEachReverse", "dependsOnInsideForEach"),

        // Test if two actions inside a for-each action can depend on another
        // action inside the same for-each action
        T("dependsOnInsideForEachSplit"),

        // Test if a for-each action can depend on an execute action
        T("dependsOnByForEach"),

        // Test if an execute action can depend on a for-each action
        T("dependsOnForEach"),

        // Test if a for-each action can depend on another for-each action
        T("dependsOnForEachForEach"),

        // Test if `dependsOn` works correctly with nested for-each actions
        T("dependsOnForEachNested"),

        // Test if a nested for-each action can depend on an execute action
        T("dependsOnByForEachNested"),

        // Test if a valid macro can be included
        T("includeValidMacro"),


        //  TODO test complex graph

        //  TODO test missing service metadata

        //  TODO test wrong cardinality

        //  TODO test missing parameters

        //  TODO test wrong parameter names

    )

    /**
     * Provides arguments for all unit tests to JUnit
     */
    @JvmStatic
    @Suppress("UNUSED")
    fun argumentProvider(): Stream<Arguments> = tests.flatMap {
      listOf(
          Arguments.arguments(it.workflowName, it.resultsName, true, false),
          Arguments.arguments(it.workflowName, it.resultsName, false, false),
          Arguments.arguments(it.workflowName, it.resultsName, true, true),
          Arguments.arguments(it.workflowName, it.resultsName, false, true)
      )
    }.stream()

    private fun readMacro(name: String): Macro {
      val fixture = Companion::class.java.getResource("fixtures/$name.yaml")!!.readText()
      return YamlUtils.readValue(fixture)
    }

    @JvmStatic
    @BeforeAll
    fun setUpAll() {
      val m1 = readMacro("validMacro")
      val m2 = readMacro("validMacro2")
      macros = mapOf(m1.id to m1, m2.id to m2)
    }
  }

  data class Expected(
      val chains: List<ProcessChain>,
      val results: Map<String, List<Any>>,
      val executedExecutableIds: Set<String> = emptySet()
  )

  private fun readWorkflow(name: String): Workflow {
    val fixture = javaClass.getResource("fixtures/$name.yaml")!!.readText()
    return YamlUtils.readValue(fixture)
  }

  private fun readProcessChains(name: String): List<Expected> {
    val fixture = javaClass.getResource("fixtures/${name}_result.yaml")!!.readText()
    return YamlUtils.readValue(fixture)
  }

  private suspend fun doTestAll(workflowName: String, resultsName: String = workflowName,
      persistState: Boolean = false, perResult: Boolean = false,
      consistencyChecker: (List<Executable>, ExecuteAction) -> Boolean = { _, _ -> true },
      shouldBeFinished: Boolean = true) {
    val workflow = readWorkflow(workflowName)
    val expectedChains = readProcessChains(resultsName)

    // validate workflow - just to be on the safe side
    assertThat(WorkflowValidator.validate(workflow, macros)).isEmpty()

    var json = JsonObject()
    val idgen = ConsecutiveID()
    var generator = ProcessChainGenerator(workflow, "/tmp/", "/out/", services,
        macros, consistencyChecker, idgen)
    assertThat(generator.isFinished()).isFalse

    if (persistState) {
      json = generator.persistState()
    }

    var results = mapOf<String, List<Any>>()
    var executedExecutableIds = setOf<String>()
    for (expected in expectedChains) {
      if (persistState) {
        generator = ProcessChainGenerator(workflow, "/tmp/", "/out/", services,
            macros, consistencyChecker, idgen)
        generator.loadState(json)
      }

      val processChains = if (perResult) {
        // check that the process chain generator does not generate too many
        // process chains if it's called too early (i.e. with results from
        // only a few of the actions created before)
        if (results.isEmpty()) {
          val pcs = generator.generate(results, executedExecutableIds)
          pcs
        } else {
          results.entries.flatMap { r ->
            val pcs = generator.generate(mapOf(r.toPair()), executedExecutableIds)
            pcs
          }
        }
      } else {
        generator.generate(results, executedExecutableIds)
      }
      assertThat(YamlUtils.mapper.writeValueAsString(processChains))
        .isEqualTo(YamlUtils.mapper.writeValueAsString(expected.chains))
      results = expected.results
      executedExecutableIds = expected.executedExecutableIds

      if (persistState) {
        json = generator.persistState()
      }
    }

    if (persistState) {
      generator = ProcessChainGenerator(workflow, "/tmp/", "/out/", services,
          macros, consistencyChecker, idgen)
      generator.loadState(json)
    }

    val processChains = generator.generate(results, executedExecutableIds)
    assertThat(processChains).isEmpty()
    if (shouldBeFinished) {
      assertThat(generator.isFinished()).isTrue
    } else {
      assertThat(generator.isFinished()).isFalse
    }
  }

  @ParameterizedTest
  @MethodSource("argumentProvider")
  fun testAll(workflowName: String, resultsName: String = workflowName,
      persistState: Boolean = false, perResult: Boolean = false,
      vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        doTestAll(workflowName, resultsName, persistState, perResult)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if a for-each action with a subsequent cp fails due to a
   * cardinality error
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachYieldCardinalityError(persistState: Boolean, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        assertThatThrownBy {
          doTestAll("forEachYieldCardinalityError", persistState = persistState)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cardinality")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test a custom output adapter splits a process chain
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun outputAdapter(persistState: Boolean, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      val pluginRegistry: PluginRegistry = mockk()
      mockkObject(PluginRegistryFactory)
      every { PluginRegistryFactory.create() } returns pluginRegistry

      val p = OutputAdapterPlugin(name = "custom", scriptFile = "custom.kts",
          supportedDataType = "custom")
      every { pluginRegistry.findOutputAdapter(any()) } returns null
      every { pluginRegistry.findOutputAdapter("custom") } returns p

      ctx.coVerify {
        try {
          doTestAll("outputAdapter", persistState = persistState)
        } finally {
          unmockkAll()
        }
      }

      ctx.completeNow()
    }
  }

  /**
   * Test if a process chain is split if the consistency checker returns `false`
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun consistencyChecker(persistState: Boolean, vertx: Vertx, ctx: VertxTestContext) {
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.coVerify {
        // let all actions through
        doTestAll("consistencyChecker", "consistencyChecker", persistState = persistState)

        // split after the first action
        doTestAll("consistencyChecker", "consistencyChecker_split", persistState = persistState,
            consistencyChecker = { executables, action ->
              executables.isEmpty() || action.id != "cp2"
            })

        // don't let any action through
        doTestAll("consistencyChecker", "consistencyChecker_false", persistState = persistState,
            consistencyChecker = { _, _ -> false }, shouldBeFinished = false)
      }

      ctx.completeNow()
    }
  }
}
