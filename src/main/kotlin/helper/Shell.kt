package helper

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.LinkedList

/**
 * Provides methods to execute shell commands
 * @author Michel Kraemer
 */
object Shell {
  private val log = LoggerFactory.getLogger(Shell::class.java)

  /**
   * Executes the given command
   * @param command the command
   * @param outputLinesToCollect the number of output lines to collect at most
   * @return the command's output (stdout and stderr)
   * @throws ExecutionException if the command failed
   */
  fun execute(command: List<String>, outputLinesToCollect: Int = 100): String {
    return execute(command, File(System.getProperty("user.dir")), outputLinesToCollect)
  }

  /**
   * Executes the given command in the given working directory
   * @param command the command
   * @param workingDir the working directory
   * @param outputLinesToCollect the number of output lines to collect at most
   * @return the command's output (stdout and stderr)
   * @throws ExecutionException if the command failed
   */
  private fun execute(command: List<String>, workingDir: File,
      outputLinesToCollect: Int = 100): String {
    val lines = LinkedList<String>()
    val joinedCommand = command.joinToString(" ")
    log.info(joinedCommand)

    val process = ProcessBuilder(command)
        .directory(workingDir)
        .redirectErrorStream(true)
        .start()

    process.inputStream.bufferedReader().forEachLine { line ->
      log.info(line)
      lines.add(line)
      if (lines.size > outputLinesToCollect) {
        lines.removeFirst()
      }
    }

    process.waitFor()

    val code = process.exitValue()
    val result = lines.joinToString("\n")
    if (code != 0) {
      log.error("Command failed with exit code: $code")
      throw ExecutionException("Failed to run `$joinedCommand'", result, code)
    }

    return result
  }

  /**
   * An exception thrown by [execute] if the command failed
   * @param message a generic error message
   * @param lastOutput the last output collected from the command before it
   * failed (may contain the actual error message issued by the command)
   * @param exitCode the command's exit code
   */
  class ExecutionException(
      message: String,
      val lastOutput: String,
      val exitCode: Int
  ) : IOException(message)
}
