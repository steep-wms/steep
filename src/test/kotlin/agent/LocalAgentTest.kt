package agent

import io.vertx.core.Vertx
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Tests for [LocalAgent]
 * @author Michel Kraemer
 */
class LocalAgentTest : AgentTest() {
  private val executorService = Executors.newCachedThreadPool()
  private val localAgentDispatcher = executorService.asCoroutineDispatcher()

  override fun createAgent(vertx: Vertx): Agent = LocalAgent(vertx, localAgentDispatcher)
}
