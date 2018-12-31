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

  private fun readWorkflow(name: String): Workflow {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("fixtures/$name.json").readText()
    return mapper.readValue(fixture)
  }

  private fun readProcessChains(name: String): List<ProcessChain> {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("fixtures/${name}_result.json").readText()
    return mapper.readValue(fixture)
  }

  private fun doTest(name: String) {
    val workflow = readWorkflow(name)
    val services = listOf(serviceCp)
    val expected = readProcessChains(name)

    val ruleSystem = RuleSystem(workflow, "/tmp/", services, ConsecutiveID())
    val processChains = ruleSystem.fire()

    assertThat(processChains).isEqualTo(expected)
  }

  /**
   * Tests if a simple workflow with a single service can be converted correctly
   */
  @Test
  fun singleService() {
    doTest("singleService")
  }

  /**
   * Tests if a simple workflow with a two independent services is converted
   * to two process chains
   */
  @Test
  fun twoIndependentServices() {
    doTest("twoIndependentServices")
  }

  /**
   * Tests if a simple workflow with a two dependent services is converted
   * to a single process chain
   */
  @Test
  fun twoDependentServices() {
    doTest("twoDependentServices")
  }

  /**
   * Tests if a simple workflow with a four services is converted to two
   * process chains
   */
  @Test
  fun twoProcessChains() {
    doTest("twoProcessChains")
  }
}
