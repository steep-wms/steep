package runtime

import helper.OutputCollector
import model.processchain.Executable

/**
 * A runtime environment for process chain executables
 * @author Michel Kraemer
 */
interface Runtime {
  /**
   * Executes a given [executable] in this runtime environment and collects
   * its output with the given [outputCollector]
   */
  fun execute(executable: Executable, outputCollector: OutputCollector)
}
