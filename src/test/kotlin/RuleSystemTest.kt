import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import model.metadata.Cardinality
import model.metadata.Service
import model.metadata.ServiceParameter
import model.processchain.Argument
import model.processchain.Executable
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [RuleSystem]
 * @author Michel Kraemer
 */
class RuleSystemTest {
  /**
   * Tests if a simple workflow with a single service can be converted correctly
   */
  @Test
  fun singleService() {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("fixtures/singleService.json").readText()
    val workflow = mapper.readValue<Workflow>(fixture)

    val services = listOf(Service("cp", "cp", "Copy", "cp", Service.Runtime.OTHER, listOf(
        ServiceParameter("input_file", "Input file", "Input file",
            Argument.Type.INPUT, Cardinality(1, 1)),
        ServiceParameter("output_file", "Output file", "Output file",
            Argument.Type.OUTPUT, Cardinality(1, 1))
    )))

    val ruleSystem = RuleSystem(workflow, "/tmp/", services)
    val processChains = ruleSystem.fire()

    assertThat(processChains).hasSize(1)
    assertThat(processChains[0].executables).hasSize(1)
    assertThat(processChains[0].executables[0].arguments).hasSize(2)
    val expected = listOf(ProcessChain(id = processChains[0].id, predecessors = emptySet(), executables = listOf(
        Executable(id = "cp", path = "cp", arguments = listOf(
            Argument(id = "input_file", value = "input_file.txt",
                type = Argument.Type.INPUT, dataType = Argument.DATA_TYPE_STRING),
            Argument(id = "output_file", value = processChains[0].executables[0].arguments[1].value,
                type = Argument.Type.OUTPUT, dataType = Argument.DATA_TYPE_STRING)
        ))
    )))

    assertThat(processChains).isEqualTo(expected)
    assertThat(processChains[0].executables[0].arguments[1].value).startsWith("/tmp/")
  }
}
