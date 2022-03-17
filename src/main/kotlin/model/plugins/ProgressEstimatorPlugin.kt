package model.plugins

import io.vertx.core.Vertx
import model.processchain.Executable
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * A progress estimator plugin is a function that can analyse the output of a
 * running service with a given ID and estimate its progress. The function has
 * the following signature:
 *
 *     suspend fun myProgressEstimator(executable: model.processchain.Executable,
 *       recentLines: List<String>, vertx: io.vertx.core.Vertx): Double?
 *
 * It takes the executable that is currently being run, a list of recently
 * collected output lines, and the Vert.x instance. It returns an estimated
 * progress between 0.0 (0%) and 1.0 (100%) or `null` if the progress could not
 * be determined. The function will be called for each output line collected
 * and the newest line is always at the end of the given list. If required, the
 * function can be a suspend function.
 */
data class ProgressEstimatorPlugin(
    override val name: String,
    override val scriptFile: String,

    /**
     * A list of IDs of the services this estimator plugin supports
     */
    val supportedServiceIds: List<String>,

    /**
     * The compiled plugin
     */
    override val compiledFunction: KFunction<Double?> = throwPluginNeedsCompile()
) : Plugin

suspend fun ProgressEstimatorPlugin.call(executable: Executable,
    recentLines: List<String>, vertx: Vertx): Double? {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(executable, recentLines, vertx)
  } else {
    this.compiledFunction.call(executable, recentLines, vertx)
  }
}
