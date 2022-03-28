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
    val text = javaClass.getResource("/fixtures/$name.yaml")!!.readText()
    return YamlUtils.readValue(text)
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
   * Validate a simple workflow with an output variable that has a value
   */
  @Test
  fun outputsWithValues() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValues"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file1"))
  }

  /**
   * Validate a simple workflow with an output variable that has a value. The
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

  /**
   * Validate a workflow with some actions with duplicate IDs
   */
  @Test
  fun duplicateId() {
    val result = WorkflowValidator.validate(readWorkflow("duplicateIds"))
    assertThat(result).hasSize(4)
    assertThat(result[0].message).contains(listOf("Duplicate identifier", "input_file1"))
    assertThat(result[1].message).contains(listOf("Duplicate identifier", "cp1"))
    assertThat(result[2].message).contains(listOf("Duplicate identifier", "cp2"))
    assertThat(result[3].message).contains(listOf("Duplicate identifier", "cp2"))
  }

  /**
   * Validate a workflow with an action that specifies a dependency to an
   * action that does not exist
   */
  @Test
  fun missingDependsOnTarget() {
    val result = WorkflowValidator.validate(readWorkflow("missingDependsOnTarget"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).containsSubsequence(listOf(
      "Unable to resolve action dependency", "cp2", "cp1"))
  }

  /**
   * Validate a workflow with input variables without a value
   */
  @Test
  fun missingInputValue() {
    val result = WorkflowValidator.validate(readWorkflow("missingInputValue"))
    assertThat(result).hasSize(2)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Input variable", "input_file1", "has no value"))
    assertThat(result[1].message).containsSubsequence(listOf(
        "Input variable", "input_file3", "has no value"))
  }
}
