package runtime

import helper.OutputCollector
import model.processchain.Executable

/**
 * A runtime environment for process chain executables
 * @author Michel Kraemer
 */
interface Runtime {
  /**
   * Executes a given [executable] in this runtime environment
   * @param outputLinesToCollect the number of output lines to collect at most
   * @return the command's output (stdout and stderr)
   */
  @Deprecated("Use the other execute method accepting an OutputCollector instead")
  fun execute(executable: Executable, outputLinesToCollect: Int = 100): String

  /**
   * Executes a given [executable] in this runtime environment and collects
   * its output with the given [outputCollector]
   */
  fun execute(executable: Executable, outputCollector: OutputCollector)
}
