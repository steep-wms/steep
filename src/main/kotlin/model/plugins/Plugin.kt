package model.plugins

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlin.reflect.KFunction

/**
 * A plugin that will be loaded during runtime and that extends Steeps's
 * functionality
 * @author Michel Kraemer
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = InitializerPlugin::class, name = "initializer"),
    JsonSubTypes.Type(value = OutputAdapterPlugin::class, name = "outputAdapter"),
    JsonSubTypes.Type(value = ProcessChainAdapterPlugin::class, name = "processChainAdapter"),
    JsonSubTypes.Type(value = ProgressEstimatorPlugin::class, name = "progressEstimator"),
    JsonSubTypes.Type(value = RuntimePlugin::class, name = "runtime")
)
interface Plugin {
  /**
   * The plugin's name
   */
  val name: String

  /**
   * The path to the plugin's script file
   */
  val scriptFile: String

  /**
   * The compiled plugin
   */
  val compiledFunction: KFunction<*>
}

inline fun <reified T> throwPluginNeedsCompile(): KFunction<T> {
  val a = object {
    fun i(): T {
      throw NotImplementedError("The plugin must be compiled first")
    }
  }
  return a::i
}
