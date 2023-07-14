package helper

/**
 * Treat a given list of nodes as an acyclic dependency graph and use
 * Kahn's algorithm to sort it topologically. Return a flat list of nodes
 * sorted in the correct order so that every node B that depends on a
 * node A comes after A.
 * @author Michel Kraemer
 */
object DependencyResolver {
  interface Node {
    val id: String
    val dependsOn: List<String>
  }

  /**
   * Will be thrown when the dependency [dependsOn] of a certain [node] could
   * not be resolved (i.e. if there is no node with the ID [dependsOn])
   */
  class MissingDependencyException(val node: String, val dependsOn: String) :
      IllegalStateException("Node `$node' depends on node `$dependsOn' " +
          "but this node does not exist.")

  /**
   * Will be thrown when a dependency cycle between some [nodes] has been
   * detected in the graph.
   */
  class DependencyCycleException(val nodes: List<Node>) :
      IllegalStateException("Detected circular dependency between " +
          "nodes ${nodes.joinToString(separator = "', `",
              prefix = "`", postfix = "'") { it.id }}")

  /**
   * Treat the given list of [nodes] as an acyclic dependency graph and use
   * Kahn's algorithm to sort it topologically. Return a flat list of nodes
   * sorted in the correct order so that every node B that depends on a
   * node A comes after A.
   *
   * Throws [MissingDependencyException] if a node depends on another node
   * that does not exist.
   *
   * Throws [DependencyCycleException] if a dependency cycle has been detected.
   */
  fun <T : Node> resolve(nodes: List<T>): List<T> {
    data class N(val n: T, val dependsOn: MutableSet<String>)

    // check if all dependencies are valid
    for (a in nodes) {
      for (d in a.dependsOn) {
        if (!nodes.any { it.id == d }) {
          throw MissingDependencyException(a.id, d)
        }
      }
    }

    val result = mutableListOf<T>()

    val nodesWithoutIncomingEdges = ArrayDeque<T>()
    val nodesToBeSorted = ArrayDeque<N>()

    // find all nodes that have no dependencies
    for (n in nodes) {
      if (n.dependsOn.isEmpty()) {
        nodesWithoutIncomingEdges.add(n)
      } else {
        nodesToBeSorted.add(N(n, n.dependsOn.toMutableSet()))
      }
    }

    // for each node N that has no dependencies, ...
    while (nodesWithoutIncomingEdges.isNotEmpty()) {
      val n = nodesWithoutIncomingEdges.removeFirst()
      result.add(n)

      // ... look for nodes that depend on N, ...
      val i = nodesToBeSorted.iterator()
      while (i.hasNext()) {
        val o = i.next()
        if (o.dependsOn.contains(n.id)) {
          // ... remove the dependency, ...
          o.dependsOn.remove(n.id)
          if (o.dependsOn.isEmpty()) {
            // ... and add the node to the queue if is has no dependencies anymore
            nodesWithoutIncomingEdges.add(o.n)
            i.remove()
          }
        }
      }
    }

    // if there are nodes that still have dependencies, there must be a
    // cycle in the dependency graph!
    if (nodesToBeSorted.isNotEmpty()) {
      throw DependencyCycleException(nodesToBeSorted.map { it.n })
    }

    return result
  }
}
