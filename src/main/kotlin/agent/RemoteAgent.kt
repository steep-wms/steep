package agent

import AddressConstants
import db.SubmissionRegistry.ProcessChainStatus
import helper.CompressedJsonObjectMessageCodec
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.Counter
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.receiveChannelHandler
import kotlinx.coroutines.isActive
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory
import java.rmi.RemoteException
import java.util.concurrent.CancellationException

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

    private val log = LoggerFactory.getLogger(RemoteAgent::class.java)

    private val AGENT_LEFT_EXCEPTION = CancellationException(
        "Agent left the cluster before process chain execution could be finished")
  }

  /**
   * Counts how many process chains have been sent to be processed throughout
   * the whole cluster
   */
  private val counter: Future<Counter>

  init {
    val sharedData = vertx.sharedData()
    val counterPromise = Promise.promise<Counter>()
    sharedData.getCounter(COUNTER_NAME, counterPromise)
    counter = counterPromise.future()
  }

  override suspend fun execute(processChain: ProcessChain): Map<String, List<Any>> {
    // create reply handler
    val replyAddress = id + "." + UniqueID.next()
    val adapter = vertx.receiveChannelHandler<Message<JsonObject>>()
    val replyConsumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
        .handler {
          it.reply("ACK") // let the peer know that we received the result
          adapter.handle(it)
        }
    log.trace("Registered handler for replies listening on $replyAddress")

    try {
      // abort when cluster node has left
      val agentLeftConsumer = vertx.eventBus().localConsumer<JsonObject>(
          AddressConstants.CLUSTER_NODE_LEFT) { clusterNodeLeftMsg ->
        val agentId = AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX +
            clusterNodeLeftMsg.body().getString("agentId")
        val mainId = id.indexOf('[').let { i -> if (i < 0) id else id.substring(0, i) }
        if (mainId == agentId && adapter.isActive) {
          adapter.cancel(AGENT_LEFT_EXCEPTION)
        }
      }

      try {
        // send process chain and wait for ACK
        val msg = json {
          obj(
            "action" to "process",
            "processChain" to JsonUtils.toJson(processChain),
            "replyAddress" to replyAddress,
            "sequence" to counter.await().andIncrement.await()
          )
        }
        vertx.eventBus().request<Any>(id, msg, deliveryOptionsOf(
            codecName = CompressedJsonObjectMessageCodec.NAME
        )).await()

        // wait for reply
        val result = adapter.receive()

        val strStatus: String? = result.body()["status"]
        return when (val status = strStatus?.let { ProcessChainStatus.valueOf(it) }) {
          ProcessChainStatus.ERROR -> {
            val errorMessage: String? = result.body()["errorMessage"]
            if (errorMessage != null) {
              throw RemoteException(errorMessage)
            } else {
              throw RemoteException("Unknown error on remote side")
            }
          }

          ProcessChainStatus.CANCELLED ->
            throw CancellationException()

          ProcessChainStatus.SUCCESS ->
            JsonUtils.fromJson(result.body()["results"])

          else ->
            throw IllegalStateException("Unknown status: $status")
        }
      } finally {
        agentLeftConsumer.unregister()
      }
    } catch (e: CancellationException) {
      if (e === AGENT_LEFT_EXCEPTION || e.cause === AGENT_LEFT_EXCEPTION ||
          e.message == AGENT_LEFT_EXCEPTION.message) {
        throw IllegalStateException(e.message)
      }
      throw e
    } finally {
      replyConsumer.unregister()
      log.trace("Unregistered handler for replies listening on $replyAddress")
    }
  }
}
