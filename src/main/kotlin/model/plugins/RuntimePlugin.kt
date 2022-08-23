package model.plugins

import com.fasterxml.jackson.annotation.JsonIgnore
import helper.OutputCollector
import io.vertx.core.Vertx
import model.processchain.Executable
import kotlin.reflect.KFunction

/**
 * A runtime plugin is a function that can run process chain executables within
 * a certain runtime environment (see [model.metadata.Service.runtime]). The
 * function has the following signature:
 *
 *     suspend fun myRuntime(executable: model.processchain.Executable,
 *       outputCollector: helper.OutputCollector, vertx: io.vertx.core.Vertx)
 *
 * It takes an executable to run, an output collector, and the Vert.x instance.
 * The executable's output should be forwarded to the output collector. If
 * required, the function can be a suspend function.
 */
data class RuntimePlugin(
    override val name: String,
    override val scriptFile: String,
    override val version: String? = null,

    /**
     * The name of the supported runtime environment
     */
    val supportedRuntime: String,

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<Unit> = throwPluginNeedsCompile()
) : Plugin

@Suppress("UNUSED_PARAMETER")
internal fun runtimePluginTemplate(executable: Executable,
    outputCollector: OutputCollector, vertx: Vertx) {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of a runtime plugin")
}
