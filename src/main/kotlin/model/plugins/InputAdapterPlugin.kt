package model.plugins

import com.fasterxml.jackson.annotation.JsonIgnore
import io.vertx.core.Vertx
import model.processchain.Argument
import model.processchain.Executable
import model.processchain.ProcessChain
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * An input adapter plugin is a function that can manipulate the inputs of
 * services depending on their data type (see [model.metadata.ServiceParameter.dataType]
 * and [model.processchain.Argument.dataType]). The function has the following
 * signature:
 *
 *     suspend fun myInputAdapter(input: model.processchain.Argument,
 *       executable: model.processchain.Executable,
 *       processChain: model.processchain.ProcessChain,
 *       vertx: io.vertx.core.Vertx): List<model.processchain.Argument>
 *
 * It takes an input argument extracted from an executable, the unmodified
 * executable itself, the corresponding process chain, and the Vert.x instance.
 * It returns a list of new arguments that will replace the input argument (in
 * other words: the input argument will be removed from the process chain and
 * the returned arguments will be inserted at the same position). This list can
 * be empty to just remove the input argument.
 *
 * In contrast to a [ProcessChainAdapterPlugin], this type of plugin will be
 * called on the local system where the service will be executed. This allows
 * you to access local resources such as the file system.
 *
 * If required, the function can be a suspend function.
 */
data class InputAdapterPlugin(
    override val name: String,
    override val scriptFile: String,
    override val version: String? = null,

    /**
     * The input data type this plugin supports
     */
    val supportedDataType: String,

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<List<Argument>> = throwPluginNeedsCompile()
) : Plugin

@Suppress("UNUSED_PARAMETER")
internal fun inputAdapterPluginTemplate(input: Argument, executable: Executable,
    processChain: ProcessChain, vertx: Vertx): List<Argument> {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of an input adapter plugin")
}

suspend fun InputAdapterPlugin.call(input: Argument, executable: Executable,
    processChain: ProcessChain, vertx: Vertx): List<Argument> {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(input, executable, processChain, vertx)
  } else {
    this.compiledFunction.call(input, executable, processChain, vertx)
  }
}
