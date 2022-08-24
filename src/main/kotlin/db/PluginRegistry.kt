package db

import model.plugins.InitializerPlugin
import model.plugins.OutputAdapterPlugin
import model.plugins.Plugin
import model.plugins.ProcessChainAdapterPlugin
import model.plugins.ProcessChainConsistencyCheckerPlugin
import model.plugins.ProgressEstimatorPlugin
import model.plugins.RuntimePlugin

/**
 * Provides access to compiled plugins
 * @author Michel Kraemer
 */
class PluginRegistry(private val compiledPlugins: List<Plugin>) {
  private val initializers = compiledPlugins.filterIsInstance<InitializerPlugin>()
      .toResolved()
  private val outputAdapters = compiledPlugins.filterIsInstance<OutputAdapterPlugin>()
      .associateBy { it.supportedDataType }
  private val processChainAdapters = compiledPlugins
      .filterIsInstance<ProcessChainAdapterPlugin>()
      .toResolved()
  private val processChainConsistencyCheckers = compiledPlugins
      .filterIsInstance<ProcessChainConsistencyCheckerPlugin>()
      .toResolved()
  private val progressEstimators = compiledPlugins.filterIsInstance<ProgressEstimatorPlugin>()
      .flatMap { p -> p.supportedServiceIds.map { it to p } }
      .toMap()
  private val runtimes = compiledPlugins.filterIsInstance<RuntimePlugin>()
      .associateBy { it.supportedRuntime }

  /**
   * Get a list of all plugins
   */
  fun getAllPlugins() = compiledPlugins

  /**
   * Get all initializers
   */
  fun getInitializers() = initializers

  /**
   * Get an output adapter that supports the given [dataType]
   */
  fun findOutputAdapter(dataType: String) = outputAdapters[dataType]

  /**
   * Get a runtime with the given [name]
   */
  fun findRuntime(name: String) = runtimes[name]

  /**
   * Get a progress estimator for a service with the given [serviceId]
   */
  fun findProgressEstimator(serviceId: String) = progressEstimators[serviceId]

  /**
   * Get all process chain adapters
   */
  fun getProcessChainAdapters() = processChainAdapters

  /**
   * Get all process chain consistency checkers
   */
  fun getProcessChainConsistencyCheckers() = processChainConsistencyCheckers
}
