package agent

import AddressConstants
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.eventbus.sendAwait
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.receiveChannelHandler
import model.processchain.ProcessChain
import java.rmi.RemoteException

/**
 * This class sends a process chain over the event bus to a remote agent and
 * waits for its results. The class automatically aborts if the node on which
 * the remote agent was deployed has left the cluster.
 * @author Michel Kraemer
 */
class RemoteAgent(override val id: String, private val vertx: Vertx) : Agent {
  override suspend fun execute(processChain: ProcessChain): Map<String, List<String>> {
    // create reply handler
    val replyAddress = id + "." + UniqueID.next()
    val adapter = vertx.receiveChannelHandler<Message<JsonObject>>()
    val replyConsumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        .handler(adapter)

    try {
      // abort when cluster node has left
      val agentLeftConsumer = vertx.eventBus().consumer<String>(
          AddressConstants.CLUSTER_NODE_LEFT) { agentLeftMsg ->
        val leftNodeId = agentLeftMsg.body()
        if (id == RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + leftNodeId) {
          adapter.cancel()
        }
      }

      try {
        // send process chain and wait for ACK
        val msg = json {
          obj(
            "processChain" to JsonUtils.toJson(processChain),
            "replyAddress" to replyAddress
          )
        }
        vertx.eventBus().sendAwait<Any>(id, msg)

        // wait for reply
        val result = adapter.receive()

        val errorMessage: String? = result.body()["errorMessage"]
        if (errorMessage != null) {
          throw RemoteException(errorMessage)
        }
        return JsonUtils.fromJson(result.body()["results"])
      } finally {
        agentLeftConsumer.unregister()
      }
    } finally {
      replyConsumer.unregister()
    }
  }
}
