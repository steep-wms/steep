package model.plugins

import com.fasterxml.jackson.annotation.JsonIgnore
import io.vertx.core.Vertx
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend

/**
 * Parameters that will be passed to a [CapabilityMatcherPlugin]
 */
interface CapabilityMatcherParams {
  /**
   * The required capability that should be matched with the collection of
   * [providedCapabilities]. In the simplest case, the plugin may just check
   * if the collection contains this string, but more complex comparisons
   * can be implemented.
   */
  val subject: String

  /**
   * The collection of provided capabilities to which the required capability
   * denoted with [subject] should be matched.
   */
  val providedCapabilities: Collection<String>

  /**
   * The collection of all required capabilities (including [subject]) for
   * reference or if complex decisions need to be made (e.g. [subject] `"A"`
   * only matches [providedCapabilities] if [allRequiredCapabilities] does not
   * contain string `"B"`).
   */
  val allRequiredCapabilities: Collection<String>
}

/**
 * A simple implementation of [CapabilityMatcherParams]
 */
data class SimpleCapabilityMatcherParams(
    override val subject: String,
    override val providedCapabilities: Collection<String>,
    override val allRequiredCapabilities: Collection<String>
) : CapabilityMatcherParams

/**
 * A capability matcher plugin is a function that can change how Steep decides
 * whether a given collection of provided capabilities satisfy a required
 * capability. The function has the following signature:
 *
 *     suspend fun myCapabilityMatcher(
 *       params: model.plugins.CapabilityMatcherParams,
 *       vertx: io.vertx.core.Vertx): Int
 *
 * The function will be called with a set of [CapabilityMatcherParams] and
 * the Vert.x instance. If required, the function can be a suspend function.
 *
 * The parameters contain a [CapabilityMatcherParams.subject] (representing
 * a single required capability) and a collection of
 * [CapabilityMatcherParams.providedCapabilities].
 *
 * For reference or if more complex decisions need to be made, the parameters
 * also contain the collection of all required capabilities (including the
 * subject).
 *
 * The function can cast a vote on whether the provided capabilities satisfy
 * the required capability denoted by [CapabilityMatcherParams.subject]. For
 * this, it returns an integer value. Positive values mean that the provided
 * capabilities match the subject. The higher the value, the more certain the
 * function is about this. A value of [Int.MAX_VALUE] means that the
 * capabilities definitely match (i.e. the plugin is absolutely certain). No
 * other plugin will be called in this case.
 *
 * Negative values mean that the provided capabilities *do not* match the
 * subject. The lower the value, the more certain the function is about this.
 * A value of [Int.MIN_VALUE] means that the plugin is absolutely certain that
 * capabilities *do not* match. No other plugin will be called in this case.
 *
 * A value of 0 (zero) means that the plugin is unable to tell if the provided
 * capabilities match the subject. The decision should be made based on other
 * criteria (i.e. by calling other plugins or by simply comparing strings as
 * a fallback).
 */
data class CapabilityMatcherPlugin(
    override val name: String,
    override val scriptFile: String,
    override val version: String? = null,

    /**
     * The compiled plugin
     */
    @JsonIgnore
    override val compiledFunction: KFunction<Int> = throwPluginNeedsCompile()
) : Plugin

@Suppress("UNUSED_PARAMETER")
internal fun capabilityMatcherPluginTemplate(params: CapabilityMatcherParams,
    vertx: Vertx): Int {
  throw NotImplementedError("This is just a template specifying the " +
      "function signature of a setup adapter plugin")
}

suspend fun CapabilityMatcherPlugin.call(params: CapabilityMatcherParams,
    vertx: Vertx): Int {
  return if (this.compiledFunction.isSuspend) {
    this.compiledFunction.callSuspend(params, vertx)
  } else {
    this.compiledFunction.call(params, vertx)
  }
}
