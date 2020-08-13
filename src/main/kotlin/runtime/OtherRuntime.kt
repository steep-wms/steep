package runtime

import helper.OutputCollector
import helper.Shell
import model.processchain.Argument
import model.processchain.Executable
import org.apache.commons.lang3.BooleanUtils

/**
 * Runs executables locally through [Shell.execute]
 * @author Michel Kraemer
 */
open class OtherRuntime : Runtime {
  override fun execute(executable: Executable, outputLinesToCollect: Int): String {
    val collector = OutputCollector(outputLinesToCollect)
    execute(executable, collector)
    return collector.output()
  }

  override fun execute(executable: Executable, outputCollector: OutputCollector) {
    val cmd = executableToCommandLine(executable)
    Shell.execute(cmd, outputCollector)
  }

  /**
   * Converts the given executable to a command line
   * @param exec the executable
   * @return the command line
   */
  private fun executableToCommandLine(exec: Executable): List<String> {
    val line = mutableListOf<String>()
    line.add(exec.path)
    for (arg in exec.arguments) {
      // only include label if the argument is not boolean or if it is `true`
      if (arg.dataType != Argument.DATA_TYPE_BOOLEAN ||
          BooleanUtils.toBoolean(arg.variable.value)) {
        if (arg.label != null) {
          line.add(arg.label)
        }
      }
      if (arg.dataType != Argument.DATA_TYPE_BOOLEAN) {
        line.add(arg.variable.value)
      }
    }
    return line
  }
}
