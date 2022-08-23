package model.plugins

import com.fasterxml.jackson.annotation.JsonIgnore
import io.vertx.core.Vertx
import model.processchain.Executable
import model.workflow.ExecuteAction
import model.workflow.Workflow
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * A process chain consistency checker plugin is a function that checks if
 * a workflow [ExecuteAction] can be added to a process chain or if adding
 * it would lead to inconsistencies that render the process chain inexecutable.
 * The function has the following signature:
 *
 *     suspend fun myProcessChainConsistencyChecker(processChain: List<model.processchain.Executable>,
 *       action: model.workflow.ExecuteAction, workflow: model.workflow.Workflow,
 *       vertx: io.vertx.core.Vertx): Boolean
 *
 * The function is called before the [ExecuteAction] is converted to an
 * [Executable] and before it is added to the process chain. It takes a list of
 * [Executable]s that have been collected so far during process chain
 * generation, the [ExecuteAction] that is about to be converted, the workflow
 * from which the process chain is being created, and the Vert.x instance. It
 * returns `true` if the [ExecuteAction] can safely be converted and added to
 * the list of [Executable]s to make up a new process chain, or `false` if
 * adding it would lead to a process chain that could not be executed later.
 *
 * If required, the function can be a suspend function.
 */
data class ProcessChainConsistencyCheckerPlugin(
    override val name: String,
    override val scriptFile: String,
    override val version: String? = null,
    override val dependsOn: List<String> = emptyList(),

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<Boolean> = throwPluginNeedsCompile()
) : DependentPlugin

@Suppress("UNUSED_PARAMETER")
internal fun processChainConsistencyCheckerPluginTemplate(processChain: List<Executable>,
    action: ExecuteAction, workflow: Workflow, vertx: Vertx): Boolean {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of a process chain consistency checker plugin")
}

suspend fun ProcessChainConsistencyCheckerPlugin.call(processChain: List<Executable>,
    action: ExecuteAction, workflow: Workflow, vertx: Vertx): Boolean {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(processChain, action, workflow, vertx)
  } else {
    this.compiledFunction.call(processChain, action, workflow, vertx)
  }
}
