package model.metadata

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import model.processchain.Argument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for the service metadata model
 * @author Michel Kraemer
 */
class ServiceTest {
  /**
   * Test if a service can be read correctly
   */
  @Test
  fun read() {
    val mapper = jacksonObjectMapper()
    val fixture = javaClass.getResource("dummy.json").readText()
    val services = mapper.readValue<List<Service>>(fixture)

    assertThat(services).hasSize(1)
    val s = services[0]
    assertThat(s.id).isEqualTo("1003")
    assertThat(s.name).isEqualTo("dummy")
    assertThat(s.description).isEqualTo("A dummy service that has all attributes")
    assertThat(s.path).isEqualTo("dummy")
    assertThat(s.runtime).isEqualTo(Service.Runtime.OTHER)

    assertThat(s.parameters).hasSize(3)
    val p0 = s.parameters[0]
    assertThat(p0.id).isEqualTo("arg1")
    assertThat(p0.name).isEqualTo("The first argument")
    assertThat(p0.description).isEqualTo("A dummy argument")
    assertThat(p0.type).isEqualTo(Argument.Type.ARGUMENT)
    assertThat(p0.cardinality).isEqualTo(Cardinality(0, 1))
    assertThat(p0.dataType).isEqualTo(Argument.DATA_TYPE_STRING)
    assertThat(p0.default).isEqualTo("foo")
    assertThat(p0.label).isEqualTo("-a")
    assertThat(p0.fileSuffix).isNull()

    val p1 = s.parameters[1]
    assertThat(p1.id).isEqualTo("input_files")
    assertThat(p1.name).isEqualTo("Input files")
    assertThat(p1.description).isEqualTo("Many input files")
    assertThat(p1.type).isEqualTo(Argument.Type.INPUT)
    assertThat(p1.cardinality).isEqualTo(Cardinality(1, Int.MAX_VALUE))
    assertThat(p1.dataType).isEqualTo("file")
    assertThat(p1.default).isNull()
    assertThat(p1.label).isEqualTo("--input")
    assertThat(p1.fileSuffix).isNull()

    val p2 = s.parameters[2]
    assertThat(p2.id).isEqualTo("output_file")
    assertThat(p2.name).isEqualTo("Output file")
    assertThat(p2.description).isEqualTo("Output file name")
    assertThat(p2.type).isEqualTo(Argument.Type.OUTPUT)
    assertThat(p2.cardinality).isEqualTo(Cardinality(1, 1))
    assertThat(p2.dataType).isEqualTo("file")
    assertThat(p2.default).isNull()
    assertThat(p2.label).isNull()
    assertThat(p2.fileSuffix).isEqualTo(".txt")

    assertThat(s.requiredCapabilities).isEqualTo(setOf("docker", "gpu"))
  }
}
