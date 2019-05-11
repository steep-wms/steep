package agent

import ConfigConstants
import db.PluginRegistryFactory
import helper.FileSystemUtils.readRecursive
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.WorkerExecutor
import io.vertx.kotlin.coroutines.awaitResult
import model.metadata.Service
import model.plugins.call
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import runtime.DockerRuntime
import runtime.OtherRuntime
import java.io.File
import kotlin.reflect.full.callSuspend

/**
 * An agent that executes process chains locally
 * @author Michel Kraemer
 */
class LocalAgent(private val vertx: Vertx) : Agent {
  override val id: String = UniqueID.next()

  private val pluginRegistry = PluginRegistryFactory.create()
  private val outputLinesToCollect = vertx.orCreateContext.config()
      .getInteger(ConfigConstants.AGENT_OUTPUT_LINES_TO_COLLECT, 100)

  private val otherRuntime by lazy { OtherRuntime() }
  private val dockerRuntime by lazy { DockerRuntime(vertx) }

  override suspend fun execute(processChain: ProcessChain): Map<String, List<Any>> {
    val outputs = processChain.executables
        .flatMap { it.arguments }
        .filter { it.type == Argument.Type.OUTPUT }
    val executables = mkdirsForOutputs(outputs) + processChain.executables

    // run executables in a separate worker executor
    val executor = vertx.createSharedWorkerExecutor(LocalAgent::class.simpleName,
        1, Long.MAX_VALUE)
    try {
      for (exec in executables) {
        execute(exec, executor)
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

  private suspend fun execute(exec: Executable, executor: WorkerExecutor) {
    if (exec.runtime == Service.RUNTIME_DOCKER) {
      executeBlocking(executor) { dockerRuntime.execute(exec, outputLinesToCollect) }
    } else if (exec.runtime == Service.RUNTIME_OTHER) {
      executeBlocking(executor) { otherRuntime.execute(exec, outputLinesToCollect) }
    } else {
      val r = pluginRegistry.findRuntime(exec.runtime) ?:
          throw IllegalStateException("Unknown runtime: `${exec.runtime}'")
      if (r.compiledFunction.isSuspend) {
        r.compiledFunction.callSuspend(exec, outputLinesToCollect, vertx)
      } else {
        executeBlocking(executor) { r.compiledFunction.call(exec, outputLinesToCollect, vertx) }
      }
    }
  }

  private suspend fun executeBlocking(executor: WorkerExecutor, block: () -> Unit) {
    awaitResult<Unit> { handler ->
      // IMPORTANT: call `executeBlocking` with `ordered = false`! Otherwise,
      // we will block other calls to `executeBlocking` in the same Vert.x
      // context, because Vert.x tries to execute them all sequentially.
      executor.executeBlocking<Unit>({ f ->
        block()
        f.complete()
      }, false, { ar ->
        handler.handle(ar)
      })
    }
  }

  /**
   * Create `mkdir` commands for all output directories
   * @param outputs the outputs
   * @return the `mkdir` commands
   */
  private fun mkdirsForOutputs(outputs: List<Argument>): List<Executable> {
    val so = outputs.map {
      if (it.dataType == Argument.DATA_TYPE_DIRECTORY) {
        it.variable.value
      } else {
        File(it.variable.value).parent
      }
    }.distinct()

    return so.chunked(100).map { w ->
      Executable(
        path = "mkdir",
        arguments = listOf(
            Argument(
                label = "-p",
                variable = ArgumentVariable(UniqueID.next(), "true"),
                type = Argument.Type.ARGUMENT,
                dataType = Argument.DATA_TYPE_BOOLEAN
            )
        ) + w.map { o ->
          Argument(
              variable = ArgumentVariable(UniqueID.next(), o),
              type = Argument.Type.ARGUMENT
          )
        }
      )
    }
  }
}
