package model.plugins

import io.vertx.core.Vertx
import model.processchain.Argument
import model.processchain.ProcessChain
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * An output adapter plugin is a function that can manipulate the output of
 * services depending on their produced data type (see [model.metadata.ServiceParameter.dataType]
 * and [model.processchain.Argument.dataType]). The function has the following
 * signature:
 *
 *     suspend fun myOutputAdapter(output: model.processchain.Argument,
 *       processChain: model.processchain.ProcessChain, vertx: io.vertx.core.Vertx): List<Any>
 *
 * It takes an output argument extracted from the executed process chain, the
 * process chain, and the Vert.x instance. It returns the generated process
 * chain results. If required, the function can be a suspend function.
 */
data class OutputAdapterPlugin(
    override val name: String,
    override val scriptFile: String,

    /**
     * The output data type this plugin supports
     */
    val supportedDataType: String,

    /**
     * The compiled plugin
     */
    override val compiledFunction: KFunction<List<String>> = throwPluginNeedsCompile()
) : Plugin

suspend fun OutputAdapterPlugin.call(output: Argument,
    processChain: ProcessChain, vertx: Vertx): List<Any> {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(output, processChain, vertx)
  } else {
    this.compiledFunction.call(output, processChain, vertx)
  }
}
