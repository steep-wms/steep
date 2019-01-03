package agent

import io.vertx.core.Vertx

/**
 * Creates [AgentRegistry] objects
 * @author Michel Kraemer
 */
object AgentRegistryFactory {
  /**
   * Create a new [AgentRegistry]
   * @param vertx the current Vert.x instance
   * @return the [AgentRegistry]
   */
  fun create(vertx: Vertx): AgentRegistry = RemoteAgentRegistry(vertx)
}
