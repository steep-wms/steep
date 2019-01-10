package agent

import AddressConstants
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.Counter
import io.vertx.kotlin.core.eventbus.sendAwait
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.shareddata.getAndIncrementAwait
import io.vertx.kotlin.coroutines.await
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
  companion object {
    /**
     * Name of a cluster-wide counter that keeps a process chain sequence number
     */
    private const val COUNTER_NAME = "RemoteAgent.Sequence"
  }

  /**
   * Counts how many process chains have been sent to be processed throughout
   * the whole cluster
   */
  private val counter: Future<Counter>

  init {
    val sharedData = vertx.sharedData()
    counter = Future.future()
    sharedData.getCounter(COUNTER_NAME, counter)
  }

  override suspend fun execute(processChain: ProcessChain): Map<String, List<String>> {
    // create reply handler
    val replyAddress = id + "." + UniqueID.next()
    val adapter = vertx.receiveChannelHandler<Message<JsonObject>>()
    val replyConsumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        .handler(adapter)

    try {
      // abort when cluster node has left
      val agentLeftConsumer = vertx.eventBus().consumer<String>(
          AddressConstants.REMOTE_AGENT_LEFT) { agentLeftMsg ->
        if (id == agentLeftMsg.body()) {
          adapter.cancel()
        }
      }

      try {
        // send process chain and wait for ACK
        val msg = json {
          obj(
            "action" to "process",
            "processChain" to JsonUtils.toJson(processChain),
            "replyAddress" to replyAddress,
            "sequence" to counter.await().getAndIncrementAwait()
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
