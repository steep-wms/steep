package db

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
    data class N(val p: T, val dependsOn: MutableSet<String>)

    // check if all dependencies are valid
    for (a in plugins) {
      for (d in a.dependsOn) {
        if (!plugins.any { it.name == d }) {
          throw IllegalArgumentException("Plugin `${a.name}' depends on " +
              "plugin `${d}', which either does not exist or is not of " +
              "the same type.")
        }
      }
    }

    val result = mutableListOf<T>()

    val nodesWithoutIncomingEdges = ArrayDeque<T>()
    val nodesToBeSorted = ArrayDeque<N>()

    // find all plugins that have no dependencies
    for (p in plugins) {
      if (p.dependsOn.isEmpty()) {
        nodesWithoutIncomingEdges.add(p)
      } else {
        nodesToBeSorted.add(N(p, p.dependsOn.toMutableSet()))
      }
    }

    // for each plugin P that has no dependencies, ...
    while (nodesWithoutIncomingEdges.isNotEmpty()) {
      val n = nodesWithoutIncomingEdges.removeFirst()
      result.add(n)

      // ... look for plugins that depend on P, ...
      val i = nodesToBeSorted.iterator()
      while (i.hasNext()) {
        val o = i.next()
        if (o.dependsOn.contains(n.name)) {
          // ... remove the dependency, ...
          o.dependsOn.remove(n.name)
          if (o.dependsOn.isEmpty()) {
            // ... and add the plugin to the queue if is has no dependencies anymore
            nodesWithoutIncomingEdges.add(o.p)
            i.remove()
          }
        }
      }
    }

    // if there are plugins that still have dependencies, there must be a
    // cycle in the dependency graph!
    if (nodesToBeSorted.isNotEmpty()) {
      throw IllegalArgumentException("Detected circular dependency between " +
          "plugins ${nodesToBeSorted.joinToString(separator = "', `",
              prefix = "`", postfix = "'") { it.p.name }}")
    }

    return result
  }
}

/**
 * Convenience function to sort a list of dependent plugins
 */
fun <T : DependentPlugin> List<T>.toResolved(): List<T> = PluginDependencyResolver.resolve(this)
