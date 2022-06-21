package db

import ConfigConstants
import com.github.zafarkhaja.semver.ParseException
import com.github.zafarkhaja.semver.Version
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import model.plugins.InitializerPlugin
import model.plugins.OutputAdapterPlugin
import model.plugins.Plugin
import model.plugins.ProcessChainAdapterPlugin
import model.plugins.ProcessChainConsistencyCheckerPlugin
import model.plugins.ProgressEstimatorPlugin
import model.plugins.RuntimePlugin
import model.plugins.initializerPluginTemplate
import model.plugins.outputAdapterPluginTemplate
import model.plugins.processChainAdapterPluginTemplate
import model.plugins.processChainConsistencyCheckerPluginTemplate
import model.plugins.progressEstimatorPluginTemplate
import model.plugins.runtimePluginTemplate
import model.plugins.wrapPluginFunction
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.jvm.kotlinFunction
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.hostConfiguration
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.BasicScriptingHost
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.util.isError
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.CompiledScriptJarsCache
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Creates instances of [PluginRegistry]
 * @author Michel Kraemer
 */
object PluginRegistryFactory {
  private val log = LoggerFactory.getLogger(PluginRegistryFactory::class.java)
  private var pluginRegistry: PluginRegistry = PluginRegistry(emptyList())
  private var lastCachedScriptFile: File? = null

  private fun createCompilationConfiguration(cacheEnabled: Boolean,
      pluginCacheDir: File): ScriptCompilationConfiguration {
    return createJvmCompilationConfigurationFromTemplate<SteepPluginScript> {
      jvm {
        dependenciesFromCurrentContext(
          wholeClasspath = true
        )
      }
      hostConfiguration(ScriptingHostConfiguration {
        jvm {
          if (cacheEnabled) {
            compilationCache(CompiledScriptJarsCache { script, scriptCompilationConfiguration ->
              val unique = compiledScriptUniqueName(script, scriptCompilationConfiguration)
              lastCachedScriptFile = File(pluginCacheDir, "${unique}.jar")
              lastCachedScriptFile
            })
          }
        }
      })
    }
  }

  /**
   * See https://github.com/Kotlin/kotlin-script-examples/blob/master/jvm/simple-main-kts/simple-main-kts/src/main/kotlin/org/jetbrains/kotlin/script/examples/simpleMainKts/scriptDef.kt
   */
  private fun compiledScriptUniqueName(script: SourceCode,
    scriptCompilationConfiguration: ScriptCompilationConfiguration): String {
    val digestWrapper = MessageDigest.getInstance("MD5")
    digestWrapper.update(script.text.toByteArray())
    scriptCompilationConfiguration.notTransientData.entries
      .sortedBy { it.key.name }
      .forEach {
        digestWrapper.update(it.key.name.toByteArray())
        digestWrapper.update(it.value.toString().toByteArray())
      }
    return digestWrapper.digest().toHexString()
  }

  private fun ByteArray.toHexString(): String = joinToString("", transform = { "%02x".format(it) })

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

    // check version numbers
    for (p in plugins) {
      val v = p.version
      if (v != null) {
        if (v.isBlank()) {
          throw IllegalStateException("Version of plugin `${p.name}' must not be empty")
        }
        try {
          // throws if the version number does not follow semver rules
          Version.valueOf(v)
        } catch (e: ParseException) {
          throw IllegalStateException("Version of plugin `${p.name}' must " +
              "follow the semantic versioning specification", e)
        }
      }
    }

    val cacheEnabled = config.getBoolean(ConfigConstants.CACHE_PLUGINS_ENABLED, false)
    val pluginCachePath = config.getString(ConfigConstants.CACHE_PLUGINS_PATH, ".cache/plugins")
    val pluginCacheDir = File(pluginCachePath)
    if (cacheEnabled && !pluginCacheDir.exists()) {
      if (!pluginCacheDir.mkdirs()) {
        throw IllegalStateException("Plugin cache directory " +
            "`${pluginCachePath}' could not be created")
      }
    }

    val compilationConfiguration = createCompilationConfiguration(cacheEnabled, pluginCacheDir)
    val scriptingHost = BasicJvmScriptingHost()
    val compiledPlugins = plugins.map { compile(it, scriptingHost,
      compilationConfiguration, vertx) }

    pluginRegistry = PluginRegistry(compiledPlugins)
  }

  private fun tryLoadPreCompiled(plugin: Plugin): KCallable<*>? {
    // check if there is a pre-compiled class file
    val scriptFile = File(plugin.scriptFile)
    val className = "${scriptFile.nameWithoutExtension.replaceFirstChar { it.titlecaseChar() }}Kt"
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
   * Compile a [plugin] with the given [scriptingHost]
   *
   * See also https://github.com/Kotlin/kotlin-script-examples for more
   * information and examples how scripts can be compiled. The examples also
   * contain code to evaluate annotations (e.g. to download dependencies or
   * import scripts from other files). This could come in handy in the future.
   */
  private suspend fun compile(plugin: Plugin, scriptingHost: BasicScriptingHost,
      compilationConfiguration: ScriptCompilationConfiguration, vertx: Vertx): Plugin {
    val f = tryLoadPreCompiled(plugin) ?: run {
      log.info("Compiling plugin `${plugin.name}' (${plugin.scriptFile})")

      val script = vertx.fileSystem().readFile(plugin.scriptFile).await().toString()
      vertx.executeBlocking<KFunction<*>> { promise ->
        var retry = false
        while (true) {
          lastCachedScriptFile = null
          val res = scriptingHost.eval(script.toScriptSource(plugin.name),
            compilationConfiguration, null)
          for (e in res.reports) {
            val msg = e.render()
            when (e.severity) {
              ScriptDiagnostic.Severity.DEBUG -> log.debug(msg)
              ScriptDiagnostic.Severity.INFO -> log.info(msg)
              ScriptDiagnostic.Severity.WARNING -> log.warn(msg)
              ScriptDiagnostic.Severity.ERROR -> log.error(msg)
              ScriptDiagnostic.Severity.FATAL -> log.error(msg)
            }
          }

          if (res.isError() && !retry && lastCachedScriptFile?.exists() == true) {
            // if this was the first attempt, delete the cache entry and try again
            log.warn("Cached plugin script could not be loaded. Recompiling ...")
            lastCachedScriptFile!!.delete()
            retry = true
            continue
          }

          val returnValue = res.valueOrThrow().returnValue
          val f = returnValue.scriptClass!!.memberFunctions.find { it.name == plugin.name }?.let {
            KFunctionWithInstance(it, returnValue.scriptInstance!!)
          } ?: throw RuntimeException("Plugin does not export a function with " +
              "name `${plugin.name}'")

          promise.complete(f)
          break
        }
      }.await()
    }

    @Suppress("UNCHECKED_CAST")
    return when (plugin) {
      is InitializerPlugin -> plugin.copy(compiledFunction = wrapPluginFunction(
          f as KFunction<Unit>, ::initializerPluginTemplate.parameters))
      is OutputAdapterPlugin -> plugin.copy(compiledFunction = wrapPluginFunction(
          f as KFunction<List<Any>>, ::outputAdapterPluginTemplate.parameters))
      is ProcessChainAdapterPlugin -> plugin.copy(compiledFunction = wrapPluginFunction(
          f as KFunction<List<ProcessChain>>, ::processChainAdapterPluginTemplate.parameters))
      is ProcessChainConsistencyCheckerPlugin -> plugin.copy(compiledFunction = wrapPluginFunction(
          f as KFunction<Boolean>, ::processChainConsistencyCheckerPluginTemplate.parameters))
      is ProgressEstimatorPlugin -> plugin.copy(compiledFunction = wrapPluginFunction(
          f as KFunction<Double?>, ::progressEstimatorPluginTemplate.parameters))
      is RuntimePlugin -> plugin.copy(compiledFunction = wrapPluginFunction(
          f as KFunction<Unit>, ::runtimePluginTemplate.parameters))
      else -> throw RuntimeException("Unknown plugin type: ${plugin::class.java}")
    }
  }

  /**
   * Create a new [PluginRegistry]
   * @return the [PluginRegistry]
   */
  fun create(): PluginRegistry = pluginRegistry
}

/**
 * A template for a class representing a compiled Steep plugin
 */
@KotlinScript(
  // fileExtension has to end in "kts". Otherwise, we'll get an error from
  // the compiler saying that the file is not a script
  fileExtension = "kts"
)
abstract class SteepPluginScript

/**
 * A Kotlin function with `this` bound to an instance
 */
private class KFunctionWithInstance<out T>(private val func: KFunction<T>,
  private val instance: Any) : KFunction<T> by func {
  private val instanceParam = func.instanceParameter ?:
  func.extensionReceiverParameter ?:
  throw IllegalArgumentException("Function does not have an instance parameter")

  override fun call(vararg args: Any?): T = func.call(instance, *args)

  override fun callBy(args: Map<KParameter, Any?>): T
      = func.callBy(args + (instanceParam to instance))

  override val parameters: List<KParameter> = func.parameters
      .filter { it != instanceParam }
      .map { KParameterWithInstance(it) }

  private class KParameterWithInstance(delegate: KParameter,
      override val index: Int = delegate.index - 1) : KParameter by delegate
}
