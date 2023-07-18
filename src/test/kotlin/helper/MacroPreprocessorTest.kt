package helper

import com.fasterxml.jackson.module.kotlin.convertValue
import helper.MacroPreprocessor.preprocess
import model.macro.Macro
import model.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.yaml.snakeyaml.Yaml
import java.util.stream.Stream

/**
 * Tests for [MacroPreprocessor]
 * @author Michel Kraemer
 */
class MacroPreprocessorTest {
  companion object {
    private val tests = listOf(
        // Test if a workflow can contain a simple include action with a
        // simple macro
        "includeSimple",

        // Test if an include action can specify a default value for a
        // macro's input parameter
        "includeDefaultInput",

        // Test if the default value of a macro's input parameter can be
        // overridden
        "includeDefaultInputOverride",

        // Test if the macro preprocessor is able to rename macro-internal
        // variables
        "includeRenameVars",

        // Test if the macro preprocessor is able to rename macro-internal
        // variables correctly, even if a macro is included twice
        "includeRenameVarsTwice",

        // Test if a macro can include another macro
        "includeNested",

        // Test if a macro can be included that contains a variable with a value
        "includeVariableWithValue",

        // Test if a macro can be included even though it contains an
        // anonymous parameter
        "includeAnonymousParameter",

        // Test if a macro with a for-each action can be included
        "includeMacroWithForEach",

        // Test if a macro can be included that contains a for-each action
        // containing an include action
        "includeMacroWithForEachWithInclude",

        // Test if a macro can be included inside a for-each action. This macro
        // contains a for-each action that includes another macro and two
        // execute actions providing another output.
        "includeMacroInsideForEachComplex",

        // Test if an execute action can depend on an include action
        "dependsOnInclude",

        // Test if an execute action can depend on an include action whose
        // macro contains another include action
        "dependsOnIncludeNested",

        // Test if an execute action can depend on an include action A whose
        // macro contains another include action B and an execute action that
        // depends on B
        "dependsOnIncludeNestedDependsOn",

        // Test if an execute action can depend on an include action whose
        // macro contains a for-each action containing another include action
        "dependsOnIncludeForEach",

        // Test if an include action can depend on another action
        "includeDependsOn",

        // Test if an include action can depend on another include action
        "includeDependsOnInclude",

        // Test if an include action inside a macro can depend on another action
        "includeDependsOnNested",
    )

    /**
     * Provides arguments for all unit tests to JUnit
     */
    @JvmStatic
    @Suppress("UNUSED")
    fun argumentProvider(): Stream<Arguments> =
        tests.map { Arguments.of(it) }.stream()
  }

  /**
   * Load a test fixture. A fixture is a YAML document consisting of three
   * objects (in this order): a list of macros, a workflow containing one or
   * more include actions, the expected workflow (i.e. the expected result
   * after applying the macro preprocessor to the workflow).
   */
  private fun loadFixture(name: String): Triple<Map<String, Macro>, Workflow, Workflow> {
    val metadataYaml = javaClass.getResource("/fixtures/$name.yaml")!!
    val yaml = Yaml()
    val objs = yaml.loadAll(metadataYaml.readText()).toList()
    val macros = YamlUtils.mapper.convertValue<List<Macro>>(objs[0]).associateBy { it.id }
    val workflow: Workflow = YamlUtils.mapper.convertValue(objs[1])
    val expected: Workflow = YamlUtils.mapper.convertValue(objs[2])
    return Triple(macros, workflow, expected)
  }

  @ParameterizedTest
  @MethodSource("argumentProvider")
  fun testAll(fixtureName: String) {
    val (macros, workflow, expected) = loadFixture(fixtureName)
    assertThat(YamlUtils.mapper.writeValueAsString(preprocess(workflow, macros)))
        .isEqualTo(YamlUtils.mapper.writeValueAsString(expected))
  }

  /**
   * Test that the preprocessor throws an exception if a macro does not exist
   */
  @Test
  fun includeMissingMacro() {
    val (_, workflow, _) = loadFixture("includeMissingMacro")
    assertThatThrownBy {
      preprocess(workflow, emptyMap())
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Unable to find macro")
  }

  /**
   * Test that the preprocessor throws an exception if an input parameter
   * is missing in the include action
   */
  @Test
  fun includeMissingInput() {
    val (macros, workflow, _) = loadFixture("includeMissingInput")
    assertThatThrownBy {
      preprocess(workflow, macros)
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Missing macro input parameter")
  }

  /**
   * Test that the preprocessor throws an exception if an output parameter
   * is missing in the include action
   */
  @Test
  fun includeMissingOutput() {
    val (macros, workflow, _) = loadFixture("includeMissingOutput")
    assertThatThrownBy {
      preprocess(workflow, macros)
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Missing macro output parameter")
  }

  /**
   * Test that the preprocessor throws an exception if it detects an include
   * cycle (e.g. macro A includes B which includes A again)
   */
  @Test
  fun includeCycle() {
    val (macros, workflow, _) = loadFixture("includeCycle")
    assertThatThrownBy {
      preprocess(workflow, macros)
    }.isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("Detected include cycle")
  }
}
