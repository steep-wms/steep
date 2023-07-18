package helper

import helper.WorkflowValidator.Companion.validate
import model.macro.Macro
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
   * Read a macro from a test fixture
   */
  private fun readMacro(name: String): Macro {
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

    assertThat(validate(w1, emptyMap())).isEmpty()
    assertThat(validate(w2, emptyMap())).isEmpty()
    assertThat(validate(w3, emptyMap())).isEmpty()
    assertThat(validate(w4, emptyMap())).isEmpty()

    val m1 = readMacro("validMacro")

    assertThat(validate(m1, emptyMap())).isEmpty()
  }

  /**
   * Validate a workflow using reserved variables (with a $ character at the
   * beginning)
   */
  @Test
  fun reservedVar() {
    val result = validate(readWorkflow("reservedVar"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains("Illegal variable ID `\$input_file1'.")
    assertThat(result[1].message).contains("Illegal variable ID `\$output_file1'.")
  }

  /**
   * Validate a simple workflow with an output variable that has a value
   */
  @Test
  fun outputsWithValues() {
    val result = validate(readWorkflow("outputsWithValues"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file1"))
  }

  /**
   * Validate a simple workflow with an include action that has an output
   * variable with a value
   */
  @Test
  fun outputsWithValuesInclude() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("outputsWithValuesInclude"),
        mapOf(macro.id to macro))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file1"))
  }

  /**
   * Validate a simple workflow with an output variable that has a value. The
   * output variable is also used multiple times.
   */
  @Test
  fun outputsWithValuesMultiple() {
    val result = validate(readWorkflow("outputsWithValuesMultiple"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file1"))
  }

  /**
   * Validate a workflow with a for-each action that has an execute action with
   * an output variable that has a value
   */
  @Test
  fun outputsWithValuesForEach() {
    val result = validate(readWorkflow("outputsWithValuesForEach"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file3"))
  }

  /**
   * Validate a workflow with a for-each action that has an include action with
   * an output variable that has a value
   */
  @Test
  fun outputsWithValuesForEachInclude() {
    val result = validate(readWorkflow("outputsWithValuesForEach"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(listOf("Output variable", "output_file3"))
  }

  /**
   * Validate nested for-each actions where one of them as well as an
   * embedded execute action has output variables with values
   */
  @Test
  fun outputsWithValuesForEachNested() {
    val result = validate(readWorkflow("outputsWithValuesForEachNested"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(listOf("Output variable", "for_output"))
    assertThat(result[1].message).contains(listOf("Output variable", "output_file"))
  }

  /**
   * Validate a workflow with some actions with duplicate IDs
   */
  @Test
  fun duplicateId() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("duplicateIds"), mapOf(macro.id to macro))
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
    assertThat(result[4].path).containsExactly("workflow", "actions[6](include my_macro)")
  }

  /**
   * Validate a workflow with an action that specifies a dependency to an
   * action that does not exist
   */
  @Test
  fun missingDependsOnTarget() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("missingDependsOnTarget"),
        mapOf(macro.id to macro))
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
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("missingInputValue"),
        mapOf(macro.id to macro))
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
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("reuseOutput"), mapOf(macro.id to macro))
    assertThat(result).hasSize(3)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Output variable", "output_file2", "used more than once"))
    assertThat(result[1].message).containsSubsequence(listOf(
        "Output variable", "output_file2", "used more than once"))
    assertThat(result[2].message).containsSubsequence(listOf(
        "Output variable", "output_file2", "used more than once"))
  }

  /**
   * Make sure a variable cannot be used more than once as an output in a macro
   */
  @Test
  fun macroReuseOutput() {
    val macro = readMacro("validMacro")
    val result = validate(readMacro("macroReuseOutput"),
        mapOf(macro.id to macro))
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
    val result = validate(readWorkflow("reuseEnumerator"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Enumerator", "i", "used more than once"))
  }

  /**
   * Make sure an enumerator cannot be used as an output
   */
  @Test
  fun enumeratorAsOutput() {
    val result = validate(readWorkflow("enumeratorAsOutput"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).containsSubsequence(listOf(
        "Enumerator", "i", "used as an output"))
  }

  /**
   * Make sure scoping issues are detected
   */
  @Test
  fun scoping() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("scoping"), mapOf(macro.id to macro))
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

  /**
   * Make sure parameter IDs are only used once in a macro
   */
  @Test
  fun duplicateParameters() {
    val result = validate(readMacro("duplicateParameters"), emptyMap())
    assertThat(result).hasSize(3)
    assertThat(result[0].message).contains("Duplicate parameter identifier `i'")
    assertThat(result[1].message).contains("Duplicate parameter identifier `i'")
    assertThat(result[2].message).contains("Output parameter `i' used as an input.")
  }

  /**
   * Make sure parameters are not declared again in the macro's 'vars'
   */
  @Test
  fun redeclaredParameters() {
    val result = validate(readMacro("redeclaredParameters"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(
        "Variable `i' already declared as macro parameter.")
    assertThat(result[1].message).contains(
        "Variable `o' already declared as macro parameter.")
  }

  /**
   * Make sure parameters are not declared again in the macro's 'vars'
   */
  @Test
  fun outputWithDefault() {
    val result = validate(readMacro("outputWithDefault"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(
        "Output parameter `o' has a default value.")
  }

  /**
   * Make sure input parameters do not have a value in the macro's actions
   */
  @Test
  fun inputParameterWithValue() {
    val result = validate(readMacro("inputParameterWithValue"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains("Variable `i' has a value.")
    assertThat(result[1].message).contains("Variable `j' has a value.")
  }

  /**
   * Input parameters must not be used as outputs
   */
  @Test
  fun inputAsOutput() {
    val result = validate(readMacro("inputAsOutput"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(
        "Input parameter `i' used as an output.")
    assertThat(result[1].message).contains(
        "Input parameter `another_i' used as an output.")
  }

  /**
   * Output parameters must not be used as inputs
   */
  @Test
  fun outputAsInput() {
    val result = validate(readMacro("outputAsInput"), emptyMap())
    assertThat(result).hasSize(4)
    assertThat(result[0].message).contains("Output parameter `o' used as an input.")
    assertThat(result[1].message).contains("Output parameter `o' used as an input.")
    assertThat(result[2].message).contains("Input variable `o' has no value.")
    assertThat(result[3].message).contains("Input variable `o' has no value.")
  }

  /**
   * Macro parameters must not be used as enumerators
   */
  @Test
  fun parameterAsEnumerator() {
    val result = validate(readMacro("parameterAsEnumerator"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(
        "Macro parameter `i' used as an enumerator.")
    assertThat(result[1].message).contains(
        "Macro parameter `o' used as an enumerator.")
  }

  /**
   * Macro parameters must not be passed multiple times
   */
  @Test
  fun unknownMacro() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("includeUnknownMacro"),
        mapOf(macro.id to macro))
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains("Macro `unknown_macro' not found.")
  }

  /**
   * Macro parameters must not be passed multiple times
   */
  @Test
  fun includeDuplicateParameter() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("includeDuplicateParameter"),
        mapOf(macro.id to macro))
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(
        "Macro parameter `i' specified more than once.")
    assertThat(result[1].message).contains(
        "Macro parameter `o' specified more than once.")
  }

  /**
   * Macro parameters must not be passed multiple times
   */
  @Test
  fun missingIncludeParameter() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("missingIncludeParameter"),
        mapOf(macro.id to macro))
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains("Missing input parameter `i'.")
    assertThat(result[1].message).contains("Missing output parameter `o'.")
  }

  /**
   * Provided macro parameters must exist in the macro definition
   */
  @Test
  fun unknownIncludeParameter() {
    val macro = readMacro("validMacro")
    val result = validate(readWorkflow("unknownIncludeParameter"),
        mapOf(macro.id to macro))
    assertThat(result).hasSize(4)
    assertThat(result[0].message).contains(
        "Macro parameter `o' specified more than once.")
    assertThat(result[1].message).contains("Unknown input parameter `j'.")
    assertThat(result[2].message).contains("Unknown input parameter `o'.")
    assertThat(result[3].message).contains("Unknown output parameter `u'.")
  }

  /**
   * Dependency cycles are not allowed
   */
  @Test
  fun dependsOnCycle() {
    val result = validate(readWorkflow("dependsOnCycle"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(
        "Detected circular dependency between actions `cp1', `cp2', `cp3'.")
  }

  /**
   * Check that dependency cycles are detected even if a dependency is missing
   */
  @Test
  fun dependsOnCycleMissing() {
    val result = validate(readWorkflow("dependsOnCycleMissing"), emptyMap())
    assertThat(result).hasSize(2)
    assertThat(result[0].message).contains(
        "Unable to resolve action dependency")
    assertThat(result[1].message).contains(
        "Detected circular dependency between actions `cp1', `cp2', `cp3'.")
  }

  /**
   * Dependency cycles are not allowed
   */
  @Test
  fun dependsOnCycleForEach() {
    val result = validate(readWorkflow("dependsOnCycleForEach"), emptyMap())
    assertThat(result).hasSize(1)
    assertThat(result[0].message).contains(
        "Detected circular dependency between actions `cp1', `for1', `cp2', `cp3'.")
  }

  /**
   * Include cycles are not allowed
   */
  @Test
  fun includeCycle() {
    val macro1 = readMacro("includeCycleMacro1")
    val macro2 = readMacro("includeCycleMacro2")
    val macros = mapOf(macro1.id to macro1, macro2.id to macro2)

    for (m in macros.values) {
      val result = validate(m, macros)
      assertThat(result).hasSize(1)
      assertThat(result[0].message).contains(
          "Detected include cycle between macros `my_macro', `another_macro'.")
    }
  }

  /**
   * Check that include cycles are detected even if a macro is missing
   */
  @Test
  fun includeCycleMissing() {
    val macro1 = readMacro("includeCycleMacro1")
    val macro2 = readMacro("includeCycleMacro2")
    val macro3 = readMacro("includeCycleMacro3")
    val macros = mapOf(macro1.id to macro1, macro2.id to macro2, macro3.id to macro3)

    for (m in macros.values.take(2)) {
      val result = validate(m, macros)
      assertThat(result).hasSize(1)
      assertThat(result[0].message).contains(
          "Detected include cycle between macros `my_macro', `another_macro'.")
    }

    val result3 = validate(macro3, macros)
    assertThat(result3).hasSize(2)
    assertThat(result3[0].message).contains(
        "Detected include cycle between macros `my_macro', `another_macro'.")
    assertThat(result3[1].message).contains(
        "Macro `missing_macro' not found.")
  }
}
