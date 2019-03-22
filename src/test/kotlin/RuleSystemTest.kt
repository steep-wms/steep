import TestMetadata.services
import com.fasterxml.jackson.module.kotlin.readValue
import helper.ConsecutiveID
import helper.JsonUtils
import io.vertx.core.json.JsonObject
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [RuleSystem]
 * @author Michel Kraemer
 */
class RuleSystemTest {
  data class Expected(
      val chains: List<ProcessChain>,
      val results: Map<String, List<String>>
  )

  private fun readWorkflow(name: String): Workflow {
    val fixture = javaClass.getResource("fixtures/$name.json").readText()
    return JsonUtils.mapper.readValue(fixture)
  }

  private fun readProcessChains(name: String): List<Expected> {
    val fixture = javaClass.getResource("fixtures/${name}_result.json").readText()
    return JsonUtils.mapper.readValue(fixture)
  }

  private fun doTest(workflowName: String, resultsName: String = workflowName,
      persistState: Boolean = false) {
    val workflow = readWorkflow(workflowName)
    val expectedChains = readProcessChains(resultsName)

    var json = JsonObject()
    val idgen = ConsecutiveID()
    var ruleSystem = RuleSystem(workflow, "/tmp/", services, idgen)
    assertThat(ruleSystem.isFinished()).isFalse()

    if (persistState) {
      json = ruleSystem.persistState()
    }

    var results = mapOf<String, List<String>>()
    for (expected in expectedChains) {
      if (persistState) {
        ruleSystem = RuleSystem(workflow, "/tmp/", services, idgen)
        ruleSystem.loadState(json)
      }

      val processChains = ruleSystem.fire(results)
      assertThat(processChains).isEqualTo(expected.chains)
      results = expected.results

      if (persistState) {
        json = ruleSystem.persistState()
      }
    }

    if (persistState) {
      ruleSystem = RuleSystem(workflow, "/tmp/", services, idgen)
      ruleSystem.loadState(json)
    }

    val processChains = ruleSystem.fire(results)
    assertThat(processChains).isEmpty()
    assertThat(ruleSystem.isFinished()).isTrue()
  }

  /**
   * Test if a simple workflow with a single service can be converted correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun singleService(persistState: Boolean) {
    doTest("singleService", persistState = persistState)
  }

  /**
   * Test if a simple workflow with a two independent services is converted
   * to two process chains
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoIndependentServices(persistState: Boolean) {
    doTest("twoIndependentServices", persistState = persistState)
  }

  /**
   * Test if a simple workflow with a two dependent services is converted
   * to a single process chain
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoDependentServices(persistState: Boolean) {
    doTest("twoDependentServices", persistState = persistState)
  }

  /**
   * Test if a simple workflow with a two dependent services is converted
   * to a single process chain even if the services are in reverse order in
   * the workflow
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoDependentServicesReverse(persistState: Boolean) {
    doTest("twoDependentServicesReverse", "twoDependentServices", persistState)
  }

  /**
   * Test if a simple workflow with a three dependent services is converted
   * to a single process chain
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun threeDependentServices(persistState: Boolean) {
    doTest("threeDependentServices", persistState = persistState)
  }

  /**
   * Test if a simple workflow with four services is converted to two
   * process chains
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoProcessChains(persistState: Boolean) {
    doTest("twoProcessChains", persistState = persistState)
  }

  /**
   * Test if a simple workflow with a four services is converted to two
   * process chains even if the results of the first process chain arrive
   * earlier than those of the second
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoProcessChainsAsyncResults(persistState: Boolean) {
    doTest("twoProcessChains", "twoProcessChainsAsyncResults", persistState)
  }

  /**
   * Test if a workflow with a service that joins the results of two
   * preceding services can be converted
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun join(persistState: Boolean) {
    doTest("join", persistState = persistState)
  }

  /**
   * Test if a workflow with a service that joins the results of two
   * preceding services. Test if the process chains are generated correctly
   * even if the results of the first service arrive earlier than those of
   * the second.
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun joinAsyncResults(persistState: Boolean) {
    doTest("join", "joinAsyncResults", persistState)
  }

  /**
   * Test if a simple workflow with a two independent services that read the
   * same file is converted to two process chains
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoIndependentServicesSameFile(persistState: Boolean) {
    doTest("twoIndependentServicesSameFile", persistState = persistState)
  }

  /**
   * Test a workflow with two services that depend on the results of one
   * preceding service
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun fork(persistState: Boolean) {
    doTest("fork", persistState = persistState)
  }

  /**
   * Test a workflow with a service that produces two outputs and another one
   * depending on both these outputs
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoOutputs(persistState: Boolean) {
    doTest("twoOutputs", persistState = persistState)
  }

  /**
   * Test a workflow with a service that produces two outputs and two subsequent
   * services each of them depending on one of these outputs
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun twoOutputsTwoServices(persistState: Boolean) {
    doTest("twoOutputsTwoServices", persistState = persistState)
  }

  /**
   * Test a workflow with a [fork] and a [join]
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun diamond(persistState: Boolean) {
    doTest("diamond", persistState = persistState)
  }

  /**
   * Test a small graph
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun smallGraph(persistState: Boolean) {
    doTest("smallGraph", persistState = persistState)
  }

  /**
   * Test if a missing parameter with a default value is correctly added as an
   * argument
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun missingDefaultParameter(persistState: Boolean) {
    doTest("missingDefaultParameter", persistState = persistState)
  }

  /**
   * Test if a service requiring Docker is converted correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun serviceWithDocker(persistState: Boolean) {
    doTest("serviceWithDocker", persistState = persistState)
  }

  /**
   * Test if a service requiring runtime arguments is converted correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun serviceWithRuntimeArgs(persistState: Boolean) {
    doTest("serviceWithRuntimeArgs", persistState = persistState)
  }

  /**
   * Test if a for-each action can be unrolled correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEach(persistState: Boolean) {
    doTest("forEach", persistState = persistState)
  }

  /**
   * Test if a for-each action with a pre-defined list of inputs can be unrolled
   * correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachPredefinedList(persistState: Boolean) {
    doTest("forEachPredefinedList", persistState = persistState)
  }

  /**
   * Test if a for-each action with a pre-defined list of inputs and two
   * sub-actions can be unrolled correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachPredefinedListTwoServices(persistState: Boolean) {
    doTest("forEachPredefinedListTwoServices", persistState = persistState)
  }

  /**
   * Test if nested for-each actions can be unrolled correctly
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachNested(persistState: Boolean) {
    doTest("forEachNested", persistState = persistState)
  }

  /**
   * Test if nested for-each actions that iterate over pre-defined lists can be
   * unrolled correctly (and in one step)
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachNestedPredefinedList(persistState: Boolean) {
    doTest("forEachNestedPredefinedList", persistState = persistState)
  }

  /**
   * Test if nested for-each actions that iterate over pre-defined lists can be
   * unrolled correctly if the inner for-each action contains an action that
   * depends on the results of an action from the outer for-each action
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachNestedPredefinedDependent(persistState: Boolean) {
    doTest("forEachNestedPredefinedDependent", persistState = persistState)
  }

  /**
   * Test if a nested for-each action that depends on the enumerator of the
   * outer for-each action and whose actions depend on the results of actions
   * from the outer for-each action can be unrolled (in other words: test
   * if a complex situation with nested for-each actions can be handled)
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachNestedComplex(persistState: Boolean) {
    doTest("forEachNestedComplex", persistState = persistState)
  }

  /**
   * Test for-each action with yield
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachYield(persistState: Boolean) {
    doTest("forEachYield", persistState = persistState)
  }

  /**
   * Test for-each action with yield and subsequent join
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachYieldJoin(persistState: Boolean) {
    doTest("forEachYieldJoin", persistState = persistState)
  }

  /**
   * Test if a for-each action with a subsequent cp fails due to a
   * cardinality error
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachYieldCardinalityError(persistState: Boolean) {
    assertThatThrownBy {
      doTest("forEachYieldCardinalityError", persistState = persistState)
    }.isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("cardinality")
  }

  /**
   * Test if a nested for-each action with a subsequent join fails due to a
   * cast error
   */
  @ParameterizedTest
  @ValueSource(strings = ["false", "true"])
  fun forEachYieldForEachCastError(persistState: Boolean) {
    assertThatThrownBy {
      doTest("forEachYieldForEachCastError", persistState = persistState)
    }.isInstanceOf(IllegalStateException::class.java)
        .hasMessageContaining("cast")
  }


//  TODO test complex graph

//  TODO test missing service metadata

//  TODO test wrong cardinality

//  TODO test missing parameters

//  TODO test wrong parameter names

}
