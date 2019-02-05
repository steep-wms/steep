import TestMetadata.services
import com.fasterxml.jackson.module.kotlin.readValue
import helper.ConsecutiveID
import helper.JsonUtils
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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

  private fun doTest(workflowName: String, resultsName: String = workflowName) {
    val workflow = readWorkflow(workflowName)
    val expectedChains = readProcessChains(resultsName)

    val ruleSystem = RuleSystem(workflow, "/tmp/", services, ConsecutiveID())
    assertThat(ruleSystem.isFinished()).isFalse()

    var results = mapOf<String, List<String>>()
    for (expected in expectedChains) {
      val processChains = ruleSystem.fire(results)
      assertThat(processChains).isEqualTo(expected.chains)
      results = expected.results
    }

    val processChains = ruleSystem.fire(results)
    assertThat(processChains).isEmpty()
    assertThat(ruleSystem.isFinished()).isTrue()
  }

  /**
   * Test if a simple workflow with a single service can be converted correctly
   */
  @Test
  fun singleService() {
    doTest("singleService")
  }

  /**
   * Test if a simple workflow with a two independent services is converted
   * to two process chains
   */
  @Test
  fun twoIndependentServices() {
    doTest("twoIndependentServices")
  }

  /**
   * Test if a simple workflow with a two dependent services is converted
   * to a single process chain
   */
  @Test
  fun twoDependentServices() {
    doTest("twoDependentServices")
  }

  /**
   * Test if a simple workflow with a two dependent services is converted
   * to a single process chain even if the services are in reverse order in
   * the workflow
   */
  @Test
  fun twoDependentServicesReverse() {
    doTest("twoDependentServicesReverse", "twoDependentServices")
  }

  /**
   * Test if a simple workflow with a three dependent services is converted
   * to a single process chain
   */
  @Test
  fun threeDependentServices() {
    doTest("threeDependentServices")
  }

  /**
   * Test if a simple workflow with a four services is converted to two
   * process chains
   */
  @Test
  fun twoProcessChains() {
    doTest("twoProcessChains")
  }

  /**
   * Test if a simple workflow with a four services is converted to two
   * process chains even if the results of the first process chain arrive
   * earlier than those of the second
   */
  @Test
  fun twoProcessChainsAsyncResults() {
    doTest("twoProcessChains", "twoProcessChainsAsyncResults")
  }

  /**
   * Test if a workflow with a service that joins the results of two
   * preceding services can be converted
   */
  @Test
  fun join() {
    doTest("join")
  }

  /**
   * Test if a workflow with a service that joins the results of two
   * preceding services. Test if the process chains are generated correctly
   * even if the results of the first service arrive earlier than those of
   * the second.
   */
  @Test
  fun joinAsyncResults() {
    doTest("join", "joinAsyncResults")
  }

  /**
   * Test if a simple workflow with a two independent services that read the
   * same file is converted to two process chains
   */
  @Test
  fun twoIndependentServicesSameFile() {
    doTest("twoIndependentServicesSameFile")
  }

  /**
   * Test a workflow with two services that depend on the results of one
   * preceding service
   */
  @Test
  fun fork() {
    doTest("fork")
  }

  /**
   * Test a workflow with a service that produces two outputs and another one
   * depending on both these outputs
   */
  @Test
  fun twoOutputs() {
    doTest("twoOutputs")
  }

  /**
   * Test a workflow with a service that produces two outputs and two subsequent
   * services each of them depending on one of these outputs
   */
  @Test
  fun twoOutputsTwoServices() {
    doTest("twoOutputsTwoServices")
  }

  /**
   * Test a workflow with a [fork] and a [join]
   */
  @Test
  fun diamond() {
    doTest("diamond")
  }

  /**
   * Test a small graph
   */
  @Test
  fun smallGraph() {
    doTest("smallGraph")
  }

  /**
   * Test if a missing parameter with a default value is correctly added as an
   * argument
   */
  @Test
  fun missingDefaultParameter() {
    doTest("missingDefaultParameter")
  }

  /**
   * Test if a service requiring Docker is converted correctly
   */
  @Test
  fun serviceWithDocker() {
    doTest("serviceWithDocker")
  }

  /**
   * Test if a for-each action can be unrolled correctly
   */
  @Test
  fun forEach() {
    doTest("forEach")
  }

  /**
   * Test if a for-each action with a pre-defined list of inputs can be unrolled
   * correctly
   */
  @Test
  fun forEachPredefinedList() {
    doTest("forEachPredefinedList")
  }

  /**
   * Test if a for-each action with a pre-defined list of inputs and two
   * sub-actions can be unrolled correctly
   */
  @Test
  fun forEachPredefinedListTwoServices() {
    doTest("forEachPredefinedListTwoServices")
  }

  /**
   * Test if nested for-each actions can be unrolled correctly
   */
  @Test
  fun forEachNested() {
    doTest("forEachNested")
  }

  /**
   * Test if nested for-each actions that iterate over pre-defined lists can be
   * unrolled correctly (and in one step)
   */
  @Test
  fun forEachNestedPredefinedList() {
    doTest("forEachNestedPredefinedList")
  }

  /**
   * Test if nested for-each actions that iterate over pre-defined lists can be
   * unrolled correctly if the inner for-each action contains an action that
   * depends on the results of an action from the outer for-each action
   */
  @Test
  fun forEachNestedPredefinedDependent() {
    doTest("forEachNestedPredefinedDependent")
  }

  /**
   * Test if a nested for-each action that depends on the enumerator of the
   * outer for-each action and whose actions depend on the results of actions
   * from the outer for-each action can be unrolled (in other words: test
   * if a complex situation with nested for-each actions can be handled)
   */
  @Test
  fun forEachNestedComplex() {
    doTest("forEachNestedComplex")
  }

//  TODO test complex graph

//  TODO test missing service metadata

//  TODO test wrong cardinality

//  TODO test missing parameters

//  TODO test wrong parameter names

}
