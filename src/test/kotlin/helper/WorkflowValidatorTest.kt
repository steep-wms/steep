package helper

import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [WorkflowValidator]
 * @author Michel Kraemer
 */
class WorkflowValidatorTest {
  /**
   * Read a workflow from a test fixture
   */
  private fun readWorkflow(name: String): Workflow {
    val text = javaClass.getResource("/fixtures/$name.json")!!.readText()
    return JsonUtils.readValue(text)
  }

  /**
   * Validated some valid workflows and makes sure the validator does not
   * return any error
   */
  @Test
  fun valid() {
    // select some representative valid workflows (could be any actually)
    val w1 = readWorkflow("LS1_2datasets")
    val w2 = readWorkflow("diamond")
    val w3 = readWorkflow("forEachNestedComplex")
    val w4 = readWorkflow("forEachYieldToInputSameInputDeferredPredefined")

    assertThat(WorkflowValidator.validate(w1)).isEmpty()
    assertThat(WorkflowValidator.validate(w2)).isEmpty()
    assertThat(WorkflowValidator.validate(w3)).isEmpty()
    assertThat(WorkflowValidator.validate(w4)).isEmpty()
  }

  /**
   * Validate a simple workflow with a output variable that has a value
   */
  @Test
  fun outputsWithValues() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValues"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file1"))
  }

  /**
   * Validate a simple workflow with a output variable that has a value. The
   * output variable is also used multiple times.
   */
  @Test
  fun outputsWithValuesMultiple() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValuesMultiple"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file1"))
  }

  /**
   * Validate a workflow with a for-each action that has an execute action with
   * an output variable that has a value
   */
  @Test
  fun outputsWithValuesForEach() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValuesForEach"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file3"))
  }

  /**
   * Validate a nested for-each actions where one of them as well as an
   * embedded execute action has output variables with values
   */
  @Test
  fun outputsWithValuesForEachNested() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValuesForEachNested"))
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(listOf("Output variable", "for_output"))
    assertThat(result[1].message).contains(listOf("Output variable", "output_file"))
  }
}
