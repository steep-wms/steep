package model.plugins

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.type.TypeFactory
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaType

/**
 * A plugin that will be loaded during runtime and that extends Steep's
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
    JsonSubTypes.Type(value = ProcessChainConsistencyCheckerPlugin::class, name = "processChainConsistencyChecker"),
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
   * The plugin's version
   */
  val version: String?

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

/**
 * Wrap a plugin function [func] and return a new one that expects the given
 * [parameters] and maps them to the parameters expected by [func], regardless
 * of their order. If [func] does not expect a parameter from [parameters], it
 * will be skipped. On the other hand, if [func] requires a parameter that is
 * not in [parameters] and that is not optional, an [IllegalArgumentException]
 * will be thrown.
 */
fun <T> wrapPluginFunction(func: KFunction<T>, parameters: List<KParameter>): KFunction<T> {
  val mapping = mutableMapOf<Int, Int>()
  for (p in func.parameters) {
    val pt = TypeFactory.defaultInstance().constructType(p.type.javaType)
    val s = parameters.find { o ->
      if (o.type == p.type) {
        true
      } else {
        val ot = TypeFactory.defaultInstance().constructType(o.type.javaType)
        if (pt.isTypeOrSuperTypeOf(ot.rawClass)) {
          pt.contentType == ot.contentType
        } else {
          false
        }
      }
    }
    if (s != null) {
      mapping[s.index] = p.index
    } else if (!p.isOptional) {
      throw IllegalArgumentException("Required plugin parameter " +
          "`${p.name}' cannot be injected. An instance of type `${p.type}' " +
          "is not available in the calling context.")
    }
  }
  if (func.isSuspend) {
    // include continuation in mapping
    mapping[parameters.size] = func.parameters.size
  }
  return WrappedKFunction(func, mapping, parameters)
}

/**
 * A wrapper around [KFunction] that calls [func] according to the given
 * parameter [mapping]. See implementation of [wrapPluginFunction].
 */
private class WrappedKFunction<out T>(private val func: KFunction<T>,
    private val mapping: Map<Int, Int>,
    override val parameters: List<KParameter>) : KFunction<T> by func {
  override fun call(vararg args: Any?): T {
    val n = func.parameters.size + if (isSuspend) 1 else 0
    val mappedArgs = arrayOfNulls<Any?>(n)
    for ((s, t) in mapping) {
      mappedArgs[t] = args[s]
    }
    return func.call(*mappedArgs)
  }
}
