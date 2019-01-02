package agent

import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.file.FileSystem
import io.vertx.kotlin.core.file.propsAwait
import io.vertx.kotlin.core.file.readDirAwait
import io.vertx.kotlin.coroutines.awaitBlocking
import model.processchain.Argument
import model.processchain.ProcessChain
import org.apache.commons.lang3.BooleanUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.LinkedList

/**
 * An agent that executes process chains locally
 * @author Michel Kraemer
 */
class LocalAgent : Agent {
  companion object {
    private val log = LoggerFactory.getLogger(Agent::class.java)
  }

  override val id: String = UniqueID.next()

  override suspend fun execute(processChain: ProcessChain): Map<String, List<String>> {
    // prepare commands
    val outputs = processChain.executables
        .flatMap { it.arguments }
        .filter { it.type == Argument.Type.OUTPUT }
    val commandLines = mutableListOf<List<String>>()
    commandLines.add(mkdirForOutputs(outputs))
    commandLines.addAll(processToCommandLines(processChain))

    // execute commands
    awaitBlocking {
      for (cmd in commandLines) {
        executeOnShell(cmd)
      }
    }

    // create list of results
    val fs = Vertx.currentContext().owner().fileSystem()
    return outputs.map { it.variable.id to readRecursive(it.variable.value, fs) }.toMap()
  }

  /**
   * Create `mkdir` commands for all output directories
   * @param outputs the outputs
   * @return the `mkdir` commands
   */
  private fun mkdirForOutputs(outputs: List<Argument>) =
      listOf("mkdir", "-p").plus(outputs.map {
        if (it.dataType == Argument.DATA_TYPE_DIRECTORY) {
          it.variable.value
        } else {
          File(it.variable.value).parent
        }
      }.distinct())

  /**
   * Converts the given process chain to a list of commands
   * @param processChain the process chain
   * @return the commands
   */
  private fun processToCommandLines(processChain: ProcessChain): List<List<String>> {
    log.debug("----- PROCESS CHAIN ${processChain.id}")

    val result = ArrayList<List<String>>()
    for (exec in processChain.executables) {
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
      log.debug(line.joinToString(" "))
      result.add(line)
    }

    log.debug("----- END OF PROCESS CHAIN ${processChain.id}")
    return result
  }

  /**
   * Executes the given command
   * @param command the command
   * @return the command's output (stdout and stderr)
   */
  private fun executeOnShell(command: List<String>): String {
    return executeOnShell(command, File(System.getProperty("user.dir")))
  }

  /**
   * Executes the given command in the given working directory
   * @param command the command
   * @param workingDir the working directory
   * @return the command's output (stdout and stderr)
   */
  private fun executeOnShell(command: List<String>, workingDir: File): String {
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
      throw ExecutionException("Failed to run `$joinedCommand'", result, code)
    }

    return result
  }

  /**
   * Recursively get all files from the given path. If the path is a file, the
   * method will return a list with only one entry: the file itself.
   * @param dirOrFile a directory or a file
   * @param fs the Vert.x file system
   * @return the list of files found
   */
  private suspend fun readRecursive(dirOrFile: String, fs: FileSystem): List<String> {
    val r = mutableListOf<String>()
    val q = ArrayDeque<String>()
    q.add(dirOrFile)
    while (!q.isEmpty()) {
      val f = q.poll()
      if (fs.propsAwait(f).isDirectory) {
        q.addAll(fs.readDirAwait(dirOrFile))
      } else {
        r.add(f)
      }
    }
    return r
  }

  private class ExecutionException(
      message: String,
      val lastOutput: String,
      val exitCode: Int
  ) : IOException(message)
}
