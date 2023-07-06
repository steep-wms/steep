package model

import com.fasterxml.jackson.module.kotlin.convertValue
import helper.YamlUtils
import model.macro.Macro
import model.metadata.Service
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * Tests for [Submission]
 * @author Michel Kraemer
 */
class SubmissionTest {
  private lateinit var services: List<Service>
  private lateinit var macros: Map<String, Macro>

  @BeforeEach
  fun setUp() {
    // load test services
    val metadataYaml = javaClass.getResource("submission_metadata.yaml")!!
    val yaml = Yaml()
    services = YamlUtils.mapper.convertValue<List<Service>>(
        yaml.load(metadataYaml.readText())
    )

    // load test macros
    val macrosYaml = javaClass.getResource("submission_macros.yaml")!!
    val macrosList = YamlUtils.mapper.convertValue<List<Macro>>(
        yaml.load(macrosYaml.readText())
    )
    macros = macrosList.associateBy { it.id }
  }

  /**
   * Test if [Submission.collectRequiredCapabilities] returns the correct
   * required capabilities for a simple workflow
   */
  @Test
  fun collectRequiredCapabilities() {
    val wu = javaClass.getResource("submission_workflow.yaml")!!
    val yaml = Yaml()
    val workflow = YamlUtils.mapper.convertValue<Workflow>(yaml.load(wu.readText()))

    val rc = Submission.collectRequiredCapabilities(workflow, services, macros)
    assertThat(rc).containsExactlyInAnyOrder("rc1", "rc2", "rc3")
  }

  /**
   * Test if [Submission.collectRequiredCapabilities] returns the correct
   * required capabilities if the workflow contains nested for-each actions
   */
  @Test
  fun collectRequiredCapabilitiesNested() {
    val wu = javaClass.getResource("submission_workflow_nested.yaml")!!
    val yaml = Yaml()
    val workflow = YamlUtils.mapper.convertValue<Workflow>(yaml.load(wu.readText()))

    val rc = Submission.collectRequiredCapabilities(workflow, services, macros)
    assertThat(rc).containsExactlyInAnyOrder("rc1", "rc2", "rc3", "rc4", "rc5")
  }

  /**
   * Test if [Submission.collectRequiredCapabilities] returns the correct
   * required capabilities if the workflow contains an include action
   */
  @Test
  fun collectRequiredCapabilitiesInclude() {
    val wu = javaClass.getResource("submission_workflow_include.yaml")!!
    val yaml = Yaml()
    val workflow = YamlUtils.mapper.convertValue<Workflow>(yaml.load(wu.readText()))

    val rc = Submission.collectRequiredCapabilities(workflow, services, macros)
    assertThat(rc).containsExactlyInAnyOrder("rc1", "rc2", "rc4", "rc5")
  }

  /**
   * Test if [Submission.collectRequiredCapabilities] throws if the workflow
   * tries to include a macro that does not exist
   */
  @Test
  fun collectRequiredCapabilitiesIncludeNotExist() {
    val wu = javaClass.getResource("submission_workflow_include.yaml")!!
    val yaml = Yaml()
    val workflow = YamlUtils.mapper.convertValue<Workflow>(yaml.load(wu.readText()))

    assertThatThrownBy {
      Submission.collectRequiredCapabilities(workflow, services, emptyMap())
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Unable to find included macro")
  }
}
