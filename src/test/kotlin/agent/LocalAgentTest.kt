package agent

import io.vertx.core.Vertx

/**
 * Tests for [LocalAgent]
 * @author Michel Kraemer
 */
class LocalAgentTest : AgentTest() {
  override fun createAgent(vertx: Vertx): Agent = LocalAgent(vertx)
}
