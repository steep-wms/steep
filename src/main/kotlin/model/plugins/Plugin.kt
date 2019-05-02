package model.plugins

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import kotlin.reflect.KFunction

/**
 * A plugin that will be loaded during runtime and that extends the
 * JobManager's functionality
 * @author Michel Kraemer
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = OutputAdapterPlugin::class, name = "outputAdapter")
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
  val compiledFunction: KFunction<*>?
}
