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
   * Validate a simple workflow with an include action that has an output
   * variable with a value
   */
  @Test
  fun outputsWithValuesInclude() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValuesInclude"))
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
   * Validate a workflow with a for-each action that has an include action with
   * an output variable that has a value
   */
  @Test
  fun outputsWithValuesForEachInclude() {
    val result = WorkflowValidator.validate(readWorkflow("outputsWithValuesForEach"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file3"))
  }

  /**
   * Validate nested for-each actions where one of them as well as an
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
    assertThat(result).hasSize(5)
    assertThat(result[0].message).contains(listOf("Duplicate identifier", "input_file1"))
    assertThat(result[0].path).containsExactly("workflow", "actions[0](execute cp)")
    assertThat(result[1].message).contains(listOf("Duplicate identifier", "cp1"))
    assertThat(result[1].path).containsExactly("workflow", "actions[2](execute cp)")
    assertThat(result[2].message).contains(listOf("Duplicate identifier", "cp2"))
    assertThat(result[2].path).containsExactly("workflow", "actions[4](for-each)")
    assertThat(result[3].message).contains(listOf("Duplicate identifier", "cp2"))
    assertThat(result[3].path).containsExactly("workflow", "actions[4](for-each)",
        "actions[0](execute cp)")
    assertThat(result[4].message).contains(listOf("Duplicate identifier", "cp3"))
    assertThat(result[4].path).containsExactly("workflow", "actions[6](include mymacro)")
  }

  /**
   * Validate a workflow with an action that specifies a dependency to an
   * action that does not exist
   */
  @Test
  fun missingDependsOnTarget() {
    val result = WorkflowValidator.validate(readWorkflow("missingDependsOnTarget"))
    assertThat(result).hasSize(2)
    assertThat(result[0].message).containsSubsequence(listOf(
      "Unable to resolve action dependency", "cp2", "cp1"))
    assertThat(result[1].message).containsSubsequence(listOf(
        "Unable to resolve action dependency", "cp4", "cp3"))
  }

  /**
   * Validate a workflow with input variables without a value
   */
  @Test
  fun missingInputValue() {
    val result = WorkflowValidator.validate(readWorkflow("missingInputValue"))
    assertThat(result).hasSize(3)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Input variable", "input_file1", "has no value"))
    assertThat(result[1].message).containsSubsequence(listOf(
        "Input variable", "input_file3", "has no value"))
    assertThat(result[2].message).containsSubsequence(listOf(
        "Input variable", "input_file4", "has no value"))
  }

  /**
   * Make sure a variable cannot be used more than once as an output
   */
  @Test
  fun reuseOutput() {
    val result = WorkflowValidator.validate(readWorkflow("reuseOutput"))
    assertThat(result).hasSize(3)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Output variable", "output_file2", "used more than once"))
    assertThat(result[1].message).containsSubsequence(listOf(
        "Output variable", "output_file2", "used more than once"))
    assertThat(result[2].message).containsSubsequence(listOf(
        "Output variable", "output_file2", "used more than once"))
  }

  /**
   * Make sure an enumerator cannot be used more than once
   */
  @Test
  fun reuseEnumerator() {
    val result = WorkflowValidator.validate(readWorkflow("reuseEnumerator"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Enumerator", "i", "used more than once"))
  }

  /**
   * Make sure an enumerator cannot be used as an output
   */
  @Test
  fun enumeratorAsOutput() {
    val result = WorkflowValidator.validate(readWorkflow("enumeratorAsOutput"))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Enumerator", "i", "used as an output"))
  }

  /**
   * Make sure scoping issues are detected
   */
  @Test
  fun scoping() {
    val result = WorkflowValidator.validate(readWorkflow("scoping"))
    assertThat(result).hasSize(5)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Variable", "output_file2", "not visible"))
    assertThat(result[0].path).containsExactly(
        "workflow", "actions[1](execute cp)")
    assertThat(result[1].message).containsSubsequence(listOf(
        "Variable", "i", "not visible"))
    assertThat(result[1].path).containsExactly(
        "workflow", "actions[2](execute cp)")
    assertThat(result[2].message).containsSubsequence(listOf(
        "Variable", "i", "not visible"))
    assertThat(result[2].path).containsExactly(
        "workflow", "actions[3](for-each)",
        "actions[0](for-each)", "actions[0](execute cp)")
    assertThat(result[3].message).containsSubsequence(listOf(
        "Variable", "k", "not visible"))
    assertThat(result[3].path).containsExactly(
        "workflow", "actions[3](for-each)", "actions[1](execute cp)")
    assertThat(result[4].message).containsSubsequence(listOf(
        "Variable", "k", "not visible"))
    assertThat(result[4].path).containsExactly(
        "workflow", "actions[3](for-each)", "actions[2](include my_macro)")
  }
}
