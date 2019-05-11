package db

import model.plugins.OutputAdapterPlugin
import model.plugins.Plugin
import model.plugins.RuntimePlugin

/**
 * Provides access to compiled plugins
 * @author Michel Kraemer
 */
class PluginRegistry(compiledPlugins: List<Plugin>) {
  private val outputAdapters = compiledPlugins.filterIsInstance<OutputAdapterPlugin>()
      .associateBy { it.supportedDataType }
  private val runtimes = compiledPlugins.filterIsInstance<RuntimePlugin>()
      .associateBy { it.supportedRuntime }

  /**
   * Get an output adapter that supports the given [dataType]
   */
  fun findOutputAdapter(dataType: String) = outputAdapters[dataType]

  /**
   * Get a runtime with the given [name]
   */
  fun findRuntime(name: String) = runtimes[name]
}
