package agent

import ConfigConstants
import db.PluginRegistryFactory
import helper.FileSystemUtils.readRecursive
import helper.Shell
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.awaitResult
import model.plugins.call
import model.processchain.Argument
import model.processchain.ProcessChain
import org.apache.commons.lang3.BooleanUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.util.ArrayList

/**
 * An agent that executes process chains locally
 * @author Michel Kraemer
 */
class LocalAgent(private val vertx: Vertx) : Agent {
  companion object {
    private val log = LoggerFactory.getLogger(LocalAgent::class.java)
  }

  override val id: String = UniqueID.next()

  private val pluginRegistry = PluginRegistryFactory.create()
  private val outputLinesToCollect = vertx.orCreateContext.config()
      .getInteger(ConfigConstants.AGENT_OUTPUT_LINES_TO_COLLECT, 100)

  override suspend fun execute(processChain: ProcessChain): Map<String, List<Any>> {
    // prepare commands
    val outputs = processChain.executables
        .flatMap { it.arguments }
        .filter { it.type == Argument.Type.OUTPUT }
    val commandLines = mutableListOf<List<String>>()
    if (outputs.isNotEmpty()) {
      commandLines.add(mkdirForOutputs(outputs))
    }
    commandLines.addAll(processToCommandLines(processChain))

    // execute commands in a separate worker executor
    val executor = vertx.createSharedWorkerExecutor(LocalAgent::class.simpleName,
        1, Long.MAX_VALUE)
    try {
      awaitResult<Unit> { handler ->
        // IMPORTANT: call `executeBlocking` with `ordered = false`! Otherwise,
        // we will block other calls to `executeBlocking` in the same Vert.x
        // context, because Vert.x tries to execute them all sequentially.
        executor.executeBlocking<Unit>({ f ->
          for (cmd in commandLines) {
            Shell.execute(cmd, outputLinesToCollect)
          }
          f.complete()
        }, false, { ar ->
          handler.handle(ar)
        })
      }
    } finally {
      executor.close()
    }

    // create list of results
    val fs = vertx.fileSystem()
    return outputs.associate {
      val outputAdapter = pluginRegistry.findOutputAdapter(it.dataType)
      it.variable.id to (outputAdapter?.call(it, processChain, vertx) ?:
          readRecursive(it.variable.value, fs))
    }
  }

  /**
   * Create `mkdir` commands for all output directories
   * @param outputs the outputs
   * @return the `mkdir` commands
   */
  private fun mkdirForOutputs(outputs: List<Argument>) =
      listOf("mkdir", "-p") + outputs.map {
        if (it.dataType == Argument.DATA_TYPE_DIRECTORY) {
          it.variable.value
        } else {
          File(it.variable.value).parent
        }
      }.distinct()

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
}
