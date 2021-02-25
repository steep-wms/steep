package model.plugins

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

    /**
     * The name of the supported runtime environment
     */
    val supportedRuntime: String,

    /**
     * The compiled plugin
     */
    override val compiledFunction: KFunction<Unit> = throwPluginNeedsCompile()
) : Plugin
