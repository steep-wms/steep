package runtime

import helper.OutputCollector
import helper.Shell
import model.processchain.Executable

/**
 * Runs executables locally through [Shell.execute]
 * @author Michel Kraemer
 */
open class OtherRuntime : Runtime {
  override fun execute(executable: Executable, outputCollector: OutputCollector) {
    val cmd = Runtime.executableToCommandLine(executable)
    Shell.execute(cmd, outputCollector)
  }
}
