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
   * @return the command's output (stdout and stderr)
   */
  fun execute(command: List<String>): String {
    return execute(command, File(System.getProperty("user.dir")))
  }

  /**
   * Executes the given command in the given working directory
   * @param command the command
   * @param workingDir the working directory
   * @return the command's output (stdout and stderr)
   */
  private fun execute(command: List<String>, workingDir: File): String {
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
      if (lines.size > 100) {
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

  class ExecutionException(
      message: String,
      val lastOutput: String,
      val exitCode: Int
  ) : IOException(message)
}
