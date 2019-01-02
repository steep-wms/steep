import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import helper.ConsecutiveID
import model.metadata.Cardinality
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [RuleSystem]
 * @author Michel Kraemer
 */
class RuleSystemTest {
  private val serviceCp = Service("cp", "cp", "Copy", "cp", Service.Runtime.OTHER, listOf(
      ServiceParameter("input_file", "Input file", "Input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output_file", "Output file", "Output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceJoin = Service("join", "join", "Join", "join.sh", Service.Runtime.OTHER, listOf(
      ServiceParameter("i", "Input files", "Many inputs files",
          Argument.Type.INPUT, Cardinality(1, Int.MAX_VALUE)),
      ServiceParameter("o", "Output file", "Single output file",
          Argument.Type.OUTPUT, Cardinality(1, 1))
  ))

  private val serviceSplit = Service("split", "split", "Split", "split.sh", Service.Runtime.OTHER, listOf(
      ServiceParameter("input", "Input file", "An input file",
          Argument.Type.INPUT, Cardinality(1, 1)),
      ServiceParameter("output", "Output files", "Multiple output files",
          Argument.Type.OUTPUT, Cardinality(1, Int.MAX_VALUE))
  ))

  data class Expected(
      val chains: List<ProcessChain>,
      val results: Map<String, List<String>>
  )

  private fun readWorkflow(name: String): Workflow {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("fixtures/$name.json").readText()
    return mapper.readValue(fixture)
  }

  private fun readProcessChains(name: String): List<Expected> {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("fixtures/${name}_result.json").readText()
    return mapper.readValue(fixture)
  }

  private fun doTest(workflowName: String, resultsName: String = workflowName) {
    val workflow = readWorkflow(workflowName)
    val services = listOf(serviceCp, serviceJoin, serviceSplit)
    val expectedChains = readProcessChains(resultsName)

    val ruleSystem = RuleSystem(workflow, "/tmp/", services, ConsecutiveID())

    var results = mapOf<String, List<String>>()
    for (expected in expectedChains) {
      val processChains = ruleSystem.fire(results)
      assertThat(processChains).isEqualTo(expected.chains)
      results = expected.results
    }

    val processChains = ruleSystem.fire(results)
    assertThat(processChains).isEmpty()
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

//  TODO test complex graph

//  TODO test missing service metadata

//  TODO test wrong cardinality

//  TODO test missing parameters

//  TODO test wrong parameter names

}
