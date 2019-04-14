import helper.JsonUtils
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import model.rules.Rule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [RuleSystem]
 * @author Michel Kraemer
 */
class RuleSystemTest {
  private val executable1 = Executable("copy", "copy.sh", listOf(
      Argument(
          label = "-p",
          variable = ArgumentVariable("preserve", "true"),
          type = Argument.Type.ARGUMENT,
          dataType = Argument.DATA_TYPE_BOOLEAN
      ),
      Argument(
          label = "-i",
          variable = ArgumentVariable("input_file", "test.txt"),
          type = Argument.Type.INPUT
      ),
      Argument(
          label = "-o",
          variable = ArgumentVariable("output_file", "test2.txt"),
          type = Argument.Type.OUTPUT
      )
  ))

  private val executable2 = Executable("join", "join.sh", listOf(
      Argument(
          label = "-i",
          variable = ArgumentVariable("input_file1", "test1.txt"),
          type = Argument.Type.INPUT
      ),
      Argument(
          label = "-i",
          variable = ArgumentVariable("input_file2", "test2.txt"),
          type = Argument.Type.INPUT
      ),
      Argument(
          label = "-o",
          variable = ArgumentVariable("output_dir", "/tmp"),
          type = Argument.Type.OUTPUT,
          dataType = Argument.DATA_TYPE_DIRECTORY
      )
  ))

  private val processChain1 = ProcessChain(executables = listOf(
      executable1
  ))

  private val processChain2 = ProcessChain(executables = listOf(
      executable1, executable2
  ))

  /**
   * Make sure the rule system does not change the process chains if there
   * are no rules
   */
  @Test
  fun noRules() {
    RuleSystem(emptyList()).use {
      val opcs = listOf(processChain1, processChain2)
      val npcs = it.apply(opcs)
      assertThat(npcs).isSameAs(opcs)
    }
  }

  /**
   * Test if a slash can be added to an output parameter of an executable
   */
  @Test
  fun executableAddSlash() {
    val rules = listOf(
        Rule(
            "Add slash", Rule.Target.EXECUTABLE,
            "(obj) => obj.id === 'join'",
            "(obj) => obj.arguments.forEach(a => { " +
                "if (a.dataType === 'directory') a.variable.value += '/' })"
        )
    )

    RuleSystem(rules).use {
      val opcs = listOf(processChain1, processChain2)
      val npcs = it.apply(opcs)
      assertThat(npcs[0]).isEqualTo(opcs[0])
      assertThat(npcs).hasSize(2)
      assertThat(npcs[1].executables[0]).isEqualTo(opcs[1].executables[0])
      assertThat(npcs[1].executables[1]).isEqualTo(opcs[1].executables[1].copy(
          arguments = opcs[1].executables[1].arguments.map { arg ->
            if (arg.dataType == Argument.DATA_TYPE_DIRECTORY) {
              arg.copy(variable = arg.variable.copy(value = arg.variable.value + "/"))
            } else {
              arg
            }
          }
      ))
    }
  }

  /**
   * Test if a required capability can be added to a process chain
   */
  @Test
  fun addRequiredCapability() {
    val rules = listOf(
        Rule(
            "Require GPU", Rule.Target.PROCESSCHAIN,
            "() => true",
            "function () { this.requiredCapabilities.push('gpu') }"
        )
    )

    RuleSystem(rules).use {
      val opcs = listOf(processChain1)
      val npcs = it.apply(opcs)
      assertThat(npcs).isEqualTo(listOf(processChain1.copy(
          requiredCapabilities = setOf("gpu"))))
    }
  }

  /**
   * Test if a subsequent rule can use the modified object of the previous rule
   */
  @Test
  fun subsequentProcessChainProcessChain() {
    val rules = listOf(
        Rule(
            "Add executable", Rule.Target.PROCESSCHAIN,
            "() => true",
            "(obj) => obj.executables.push(${JsonUtils.mapper.writeValueAsString(executable2)})"
        ),
        Rule(
            "Remove executable", Rule.Target.PROCESSCHAIN,
            "() => true",
            "(obj) => obj.executables.shift()"
        )
    )

    RuleSystem(rules).use {
      val opcs = listOf(ProcessChain(executables = listOf(executable1)))
      val npcs = it.apply(opcs)
      assertThat(npcs).isEqualTo(listOf(ProcessChain(
          id = opcs[0].id, executables = listOf(executable2))))
    }
  }

  /**
   * Test if a subsequent rule can use the modified object of the previous rule
   */
  @Test
  fun subsequentProcessChainExecutable() {
    val rules = listOf(
        Rule(
            "Add executable", Rule.Target.PROCESSCHAIN,
            "function () { return true }",
            """
              function () {
                this.executables.push({
                  id: "dummy",
                  path: "dummy.sh",
                  arguments: []
                });
              }
            """.trimIndent()
        ),
        Rule(
            "Add argument", Rule.Target.EXECUTABLE,
            "() => true",
            """
              function () {
                this.arguments.push({
                  id: "arg",
                  label: "-i",
                  variable: {
                    id: "argvar",
                    value: "value"
                  },
                  type: "argument"
                });
              }
            """.trimIndent()
        )
    )

    RuleSystem(rules).use {
      val opcs = listOf(ProcessChain())
      val npcs = it.apply(opcs)
      assertThat(npcs).isEqualTo(listOf(ProcessChain(
          id = opcs[0].id,
          executables = listOf(
              Executable(
                  id = "dummy",
                  path = "dummy.sh",
                  arguments = listOf(
                      Argument(
                          id = "arg",
                          label = "-i",
                          variable = ArgumentVariable("argvar", "value"),
                          type = Argument.Type.ARGUMENT
                      )
                  )
              )
          )
      )))
    }
  }

  /**
   * Test if two rules in the wrong order do not affect each other
   */
  @Test
  fun subsequentWrongOrder() {
    val rules = listOf(
        Rule(
            "Remove arguments", Rule.Target.EXECUTABLE,
            "() => true",
            "(obj) => obj.arguments = []" // should never be called
        ),
        Rule(
            "Add executable", Rule.Target.PROCESSCHAIN,
            "() => true",
            "(obj) => obj.executables.push(${JsonUtils.mapper.writeValueAsString(executable2)})"
        )
    )

    RuleSystem(rules).use {
      val opcs = listOf(ProcessChain())
      val npcs = it.apply(opcs)
      assertThat(npcs).isEqualTo(listOf(ProcessChain(
          id = opcs[0].id, executables = listOf(executable2))))
    }
  }
}
