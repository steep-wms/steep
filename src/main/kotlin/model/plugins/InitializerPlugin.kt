package model.plugins

import Controller
import Scheduler
import com.fasterxml.jackson.annotation.JsonIgnore
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
    override val version: String? = null,
    override val dependsOn: List<String> = emptyList(),

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<Unit> = throwPluginNeedsCompile()
) : DependentPlugin

@Suppress("UNUSED_PARAMETER")
internal fun initializerPluginTemplate(vertx: Vertx) {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of an initializer plugin")
}

suspend fun InitializerPlugin.call(vertx: Vertx) {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(vertx)
  } else {
    this.compiledFunction.call(vertx)
  }
}
