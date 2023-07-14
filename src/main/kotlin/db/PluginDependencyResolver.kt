package db

import helper.DependencyResolver
import model.plugins.DependentPlugin

/**
 * Sorts plugins based on their dependencies to other plugins
 * @author Michel Kraemer
 */
object PluginDependencyResolver {
  /**
   * Treat the given list of [plugins] as an acyclic dependency graph and use
   * Kahn's algorithm to sort it topologically. Return a flat list of plugins
   * sorted in the correct order so that every plugin B that depends on a
   * plugin A comes after A.
   */
  fun <T : DependentPlugin> resolve(plugins: List<T>): List<T> {
    data class PN(val p: T) : DependencyResolver.Node {
      override val id: String = p.name
      override val dependsOn: List<String> = p.dependsOn
    }

    val result = try {
      DependencyResolver.resolve(plugins.map { PN(it) })
    } catch (e: DependencyResolver.MissingDependencyException) {
      throw IllegalArgumentException("Plugin `${e.node}' depends on " +
          "plugin `${e.dependsOn}', which either does not exist or is not of " +
          "the same type.", e)
    } catch (e: DependencyResolver.DependencyCycleException) {
      throw IllegalArgumentException("Detected circular dependency between " +
          "plugins ${e.nodes.joinToString(separator = "', `",
              prefix = "`", postfix = "'") { it.id }}")
    }

    return result.map { it.p }
  }
}

/**
 * Convenience function to sort a list of dependent plugins
 */
fun <T : DependentPlugin> List<T>.toResolved(): List<T> = PluginDependencyResolver.resolve(this)
