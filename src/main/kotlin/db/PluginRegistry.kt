package db

import model.plugins.OutputAdapterPlugin
import model.plugins.Plugin

/**
 * Provides access to compiled plugins
 * @author Michel Kraemer
 */
class PluginRegistry(compiledPlugins: List<Plugin>) {
  private val outputAdapters = compiledPlugins.filterIsInstance(
      OutputAdapterPlugin::class.java)

  /**
   * Get the first output adapter that supports the given [dataType]
   */
  fun findOutputAdapter(dataType: String) = outputAdapters.find {
    it.supportedDataType == dataType }
}
