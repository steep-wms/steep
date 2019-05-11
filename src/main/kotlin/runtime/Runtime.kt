package runtime

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
  fun execute(executable: Executable, outputLinesToCollect: Int = 100): String
}
