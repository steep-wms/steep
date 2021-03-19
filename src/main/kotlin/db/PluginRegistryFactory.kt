package db

import ConfigConstants
import helper.OutputCollector
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
import model.processchain.Executable
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.kotlinFunction

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

  private fun tryLoadPreCompiled(plugin: Plugin): KCallable<*>? {
    // check if there is a pre-compiled class file
    val scriptFile = File(plugin.scriptFile)
    val className = "${scriptFile.nameWithoutExtension.capitalize()}Kt"
    val classFileName = "$className.class"
    if (!File(scriptFile.parent, classFileName).exists()) {
      return null
    }

    // try to load class file
    log.info("Loading pre-compiled plugin `${plugin.name}' (${plugin.scriptFile}[$classFileName])")
    return try {
      val cl = URLClassLoader(arrayOf(scriptFile.parentFile.toURI().toURL()), javaClass.classLoader)
      val cls = cl.loadClass(className)
      val f = cls.methods.find { it.name == plugin.name }
      if (f == null) {
        log.error("Pre-compiled plugin does not contain a function named `${plugin.name}'")
        return null
      }
      f.kotlinFunction
    } catch (t: Throwable) {
      log.error("Could not load pre-compiled plugin `${plugin.name}'", t)
      null
    }
  }

  /**
   * Compile a [plugin] with the given script [engine]
   */
  private suspend fun compile(plugin: Plugin, engine: ScriptEngine, vertx: Vertx): Plugin {
    val f = tryLoadPreCompiled(plugin) ?: run {
      log.info("Compiling plugin `${plugin.name}' (${plugin.scriptFile})")

      val script = vertx.fileSystem().readFileAwait(plugin.scriptFile).toString()
      vertx.executeBlockingAwait<KFunction<*>> { promise ->
        val bindings = engine.createBindings()

        engine.eval(script + """

          jsr223Bindings["export"] = ::${plugin.name}
        """.trimIndent(), bindings)

        promise.complete(bindings["export"] as KFunction<*>? ?: throw RuntimeException(
            "Plugin does not export a function with name `${plugin.name}'"))
      }
    }

    @Suppress("UNCHECKED_CAST")
    return when (plugin) {
      is InitializerPlugin -> plugin.copy(compiledFunction = f as KFunction<Unit>)
      is OutputAdapterPlugin -> plugin.copy(compiledFunction = f as KFunction<List<String>>)
      is ProcessChainAdapterPlugin -> plugin.copy(compiledFunction = f as KFunction<List<ProcessChain>>)
      is ProgressEstimatorPlugin -> {
        if (plugin.supportedServiceId != null) {
          log.warn("Progress estimator plugin `${plugin.name}' uses the " +
              "deprecated parameter `supportedServiceId', which will be removed " +
              "in Steep 6.0.0. Please use `supportedServiceIds' instead.")
        }
        plugin.copy(compiledFunction = f as KFunction<Double?>)
      }
      is RuntimePlugin -> {
        if (isDeprecatedRuntime(f as KFunction<*>)) {
          log.warn("Runtime plugin `${plugin.name}' uses a deprecated function " +
              "signature, which will be removed in Steep 6.0.0. Please update " +
              "the plugin as soon as possible!")
          val kf = f as KFunction<String>
          val nf = if (kf.isSuspend) {
            object {
              suspend fun i(exec: Executable, outputCollector: OutputCollector, vertx: Vertx) {
                val output = kf.callSuspend(exec, 100, vertx)
                outputCollector.collect(output)
              }
            }::i
          } else {
            object {
              fun i(exec: Executable, outputCollector: OutputCollector, vertx: Vertx) {
                val output = kf.call(exec, 100, vertx)
                outputCollector.collect(output)
              }
            }::i
          }
          plugin.copy(compiledFunction = nf)
        } else {
          plugin.copy(compiledFunction = f as KFunction<Unit>)
        }
      }
      else -> throw RuntimeException("Unknown plugin type: ${plugin::class.java}")
    }
  }

  private fun isDeprecatedRuntime(f: KFunction<*>): Boolean {
    if (f.parameters.size != 3) {
      return false
    }
    val paramType = f.parameters[1].type.javaType
    if (paramType !is Class<*>) {
      return false
    }
    if (!Int::class.java.isAssignableFrom(paramType)) {
      return false
    }
    val returnType = f.returnType.javaType
    if (returnType !is Class<*>) {
      return false
    }
    if (!String::class.java.isAssignableFrom(returnType)) {
      return false
    }
    return true
  }

  /**
   * Create a new [PluginRegistry]
   * @return the [PluginRegistry]
   */
  fun create(): PluginRegistry = pluginRegistry
}
