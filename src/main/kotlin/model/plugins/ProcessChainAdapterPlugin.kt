package model.plugins

import com.fasterxml.jackson.annotation.JsonIgnore
import io.vertx.core.Vertx
import model.processchain.Argument
import model.processchain.ProcessChain
import model.workflow.Workflow
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * A process chain adapter plugin is a function that can manipulate generated
 * process chains before they are executed. The function has the following
 * signature:
 *
 *     suspend fun myProcessChainAdapter(processChains: List<model.processchain.ProcessChain>,
 *       workflow: model.workflow.Workflow, vertx: io.vertx.core.Vertx): List<model.processchain.ProcessChain>
 *
 * It takes a list of generated process chains, a reference to the workflow from
 * which the process chains have been generated, and the Vert.x instance. It
 * returns a new list of process chains to execute or the given list if no
 * modification was made. If required, the function can be a suspend function.
 */
data class ProcessChainAdapterPlugin(
    override val name: String,
    override val scriptFile: String,
    override val version: String? = null,
    override val dependsOn: List<String> = emptyList(),

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<List<ProcessChain>> = throwPluginNeedsCompile()
) : DependentPlugin

@Suppress("UNUSED_PARAMETER")
internal fun processChainAdapterPluginTemplate(processChains: List<ProcessChain>,
    workflow: Workflow, vertx: Vertx): List<ProcessChain> {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of a process chain adapter plugin")
}

suspend fun ProcessChainAdapterPlugin.call(processChains: List<ProcessChain>,
    workflow: Workflow, vertx: Vertx): List<ProcessChain> {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(processChains, workflow, vertx)
  } else {
    this.compiledFunction.call(processChains, workflow, vertx)
  }
}
