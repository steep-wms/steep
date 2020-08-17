package db

import ConfigConstants
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.core.file.readFileAwait
import model.plugins.InitializerPlugin
import model.plugins.OutputAdapterPlugin
import model.plugins.Plugin
import model.plugins.ProcessChainAdapterPlugin
import model.plugins.ProgressEstimatorPlugin
import model.plugins.RuntimePlugin
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import kotlin.reflect.KFunction

/**
 * Creates instances of [PluginRegistry]
 * @author Michel Kraemer
 */
object PluginRegistryFactory {
  private val log = LoggerFactory.getLogger(PluginRegistryFactory::class.java)
  private var pluginRegistry: PluginRegistry = PluginRegistry(emptyList())

  /**
   * Initialize the plugin factory and compile all plugins
   */
  suspend fun initialize(vertx: Vertx, config: JsonObject = vertx.orCreateContext.config()) {
    val paths = config.getValue(ConfigConstants.PLUGINS) ?: throw IllegalStateException(
        "Missing configuration item `${ConfigConstants.PLUGINS}'")

    val pathList = when (paths) {
      is JsonArray -> paths.list.map { it as String }
      is String -> listOf(paths)
      else -> throw IllegalStateException("Configuration item " +
          "`${ConfigConstants.PLUGINS}' must either be a string or an array")
    }

    val plugins = (object : AbstractFileRegistry() {
      suspend fun find(): List<Plugin> = find(pathList, vertx)
    }).find()

    val engine = ScriptEngineManager().getEngineByExtension("kts")
    val compiledPlugins = plugins.map { compile(it, engine, vertx) }

    pluginRegistry = PluginRegistry(compiledPlugins)
  }

  /**
   * Compile a [plugin] with the given script [engine]
   */
  private suspend fun compile(plugin: Plugin, engine: ScriptEngine, vertx: Vertx): Plugin {
    log.info("Compiling plugin `${plugin.name}' (${plugin.scriptFile})")

    val script = vertx.fileSystem().readFileAwait(plugin.scriptFile).toString()
    val f = vertx.executeBlockingAwait<KFunction<*>> { promise ->
      val bindings = engine.createBindings()

      engine.eval(script + """

        jsr223Bindings["export"] = ::${plugin.name}
      """.trimIndent(), bindings)

      promise.complete(bindings["export"] as KFunction<*>? ?: throw RuntimeException(
          "Plugin does not export a function with name `${plugin.name}'"))
    }

    @Suppress("UNCHECKED_CAST")
    return when (plugin) {
      is InitializerPlugin -> plugin.copy(compiledFunction = f as KFunction<Unit>)
      is OutputAdapterPlugin -> plugin.copy(compiledFunction = f as KFunction<List<String>>)
      is ProcessChainAdapterPlugin -> plugin.copy(compiledFunction = f as KFunction<List<ProcessChain>>)
      is ProgressEstimatorPlugin -> plugin.copy(compiledFunction = f as KFunction<Double?>)
      is RuntimePlugin -> plugin.copy(compiledFunction = f as KFunction<String>)
      else -> throw RuntimeException("Unknown plugin type: ${plugin::class.java}")
    }
  }

  /**
   * Create a new [PluginRegistry]
   * @return the [PluginRegistry]
   */
  fun create(): PluginRegistry = pluginRegistry
}
