package model.plugins

import Controller
import Scheduler
import io.vertx.core.Vertx
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * An initializer plugin is a function that will be called during the
 * initialization phase of Steep just before all verticles (such as the
 * [Controller] or the [Scheduler]) are deployed. The function has the
 * following signature:
 *
 *     suspend fun myInitializer(vertx: io.vertx.core.Vertx)
 *
 * If required, the function can be a suspend function.
 */
data class InitializerPlugin(
    override val name: String,
    override val scriptFile: String,

    /**
     * The compiled plugin
     */
    override val compiledFunction: KFunction<Unit> = throwPluginNeedsCompile()
) : Plugin

suspend fun InitializerPlugin.call(vertx: Vertx) {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(vertx)
  } else {
    this.compiledFunction.call(vertx)
  }
}
