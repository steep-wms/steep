package model.plugins

import com.fasterxml.jackson.annotation.JsonIgnore
import io.vertx.core.Vertx
import model.setup.Setup
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * A setup adapter plugin is a function that can modify a [model.setup.Setup]
 * before one or more VMs are created from it. The function has the following
 * signature:
 *
 *     suspend fun mySetupAdapter(setup: model.setup.Setup,
 *       requiredCapabilities: Collection<String>,
 *       vertx: io.vertx.core.Vertx): model.setup.Setup
 *
 * The function will be called with a setup to modify and a collection of
 * required capabilities the modified setup should meet. The function should
 * return a new setup instance or the original one if no modifications were
 * necessary. If required, the function can be a suspend function.
 */
data class SetupAdapterPlugin(
    override val name: String,
    override val scriptFile: String,
    override val version: String? = null,
    override val dependsOn: List<String> = emptyList(),

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<Setup> = throwPluginNeedsCompile()
) : DependentPlugin

@Suppress("UNUSED_PARAMETER")
internal fun setupAdapterPluginTemplate(setup: Setup,
    requiredCapabilities: Collection<String>, vertx: Vertx): Setup {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of a setup adapter plugin")
}

suspend fun SetupAdapterPlugin.call(setup: Setup,
    requiredCapabilities: Collection<String>, vertx: Vertx): Setup {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(setup, requiredCapabilities, vertx)
  } else {
    this.compiledFunction.call(setup, requiredCapabilities, vertx)
  }
}
