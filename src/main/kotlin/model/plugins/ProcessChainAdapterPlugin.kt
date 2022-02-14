package model.plugins

import io.vertx.core.Vertx
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
 *       vertx: io.vertx.core.Vertx): List<model.processchain.ProcessChain>
 *
 * It takes a list of generated process chains and the Vert.x instance. It
 * returns a new list of process chains to execute or the given list if no
 * modification was made. If required, the function can be a suspend function.
 */
data class ProcessChainAdapterPlugin(
    override val name: String,
    override val scriptFile: String,
    override val dependsOn: List<String> = emptyList(),

    /**
     * The compiled plugin
     */
    override val compiledFunction: KFunction<List<ProcessChain>> = throwPluginNeedsCompile()
) : DependentPlugin

@Deprecated("Please pass the workflow for the process chains too",
  ReplaceWith("call(processChains, workflowForProcessChains, vertx)", "model.workflow.Workflow")
)
suspend fun ProcessChainAdapterPlugin.call(processChains: List<ProcessChain>,
    vertx: Vertx): List<ProcessChain> = call(processChains, Workflow(), vertx)

suspend fun ProcessChainAdapterPlugin.call(processChains: List<ProcessChain>,
    workflow: Workflow, vertx: Vertx): List<ProcessChain> {
  val arguments = if (this.compiledFunction.parameters.size == 2) {
    arrayOf(processChains, vertx)
  } else {
    arrayOf(processChains, workflow, vertx)
  }
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(*arguments)
  } else {
    this.compiledFunction.call(*arguments)
  }
}
