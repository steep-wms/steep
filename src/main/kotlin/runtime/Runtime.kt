package runtime

import helper.OutputCollector
import model.processchain.Argument
import model.processchain.Executable
import org.apache.commons.lang3.BooleanUtils

/**
 * A runtime environment for process chain executables
 * @author Michel Kraemer
 */
interface Runtime {
  companion object {
    /**
     * Converts the given executable to a command line
     * @param exec the executable
     * @return the command line
     */
    fun executableToCommandLine(exec: Executable): List<String> {
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

  /**
   * Executes a given [executable] in this runtime environment and collects
   * its output with the given [outputCollector]
   */
  fun execute(executable: Executable, outputCollector: OutputCollector)
}
