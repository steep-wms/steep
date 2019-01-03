import agent.LocalAgent
import agent.RemoteAgentRegistry
import helper.JsonUtils
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import model.processchain.ProcessChain
import org.slf4j.LoggerFactory

/**
 * The JobManager's main API entry point
 * @author Michel Kraemer
 */
class JobManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(JobManager::class.java)
  }

  override suspend fun start() {
    // deploy remote agent
    val agentEnabled = config.getBoolean(ConfigConstants.AGENT_ENABLED, true)
    if (agentEnabled) {
      // consume process chains and run a local agent for each of them
      val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.nodeId
      vertx.eventBus().consumer<JsonObject>(address, this::onProcessChain)

      // register remote agent
      val rar = RemoteAgentRegistry(vertx)
      rar.register(Main.nodeId)

      log.info("Remote agent `${Main.nodeId}' successfully deployed")
    }

    // deploy HTTP server
    val httpEnabled = config.getBoolean(ConfigConstants.HTTP_ENABLED, true)
    if (httpEnabled) {
      val host = config.getString(ConfigConstants.HTTP_HOST, "localhost")
      val port = config.getInteger(ConfigConstants.HTTP_PORT, 8080)

      val options = HttpServerOptions()
          .setCompressionSupported(true)
      val server = vertx.createHttpServer(options)
      val router = Router.router(vertx)



      server.requestHandler(router).listenAwait(port, host)

      log.info("JobManager deployed to http://$host:$port")
    }
  }

  /**
   * Extract the process chain and a reply address from the given message and
   * send an acknowledgement. Execute the process chain asynchronously with a
   * [LocalAgent] and send the results to the reply address
   * @param msg the message containing the process chain and the reply address
   */
  private fun onProcessChain(msg: Message<JsonObject>) {
    try {
      // parse message
      val jsonObj: JsonObject = msg.body()
      val replyAddress: String = jsonObj["replyAddress"]
      val processChain = JsonUtils.fromJson<ProcessChain>(jsonObj["processChain"])

      // run the local agent and return its results
      launch {
        val answer = executeProcessChain(processChain)
        vertx.eventBus().send(replyAddress, answer)
      }

      // send acknowledgement
      msg.reply("ACK")
    } catch (e: Throwable) {
      msg.fail(400, e.message)
    }
  }

  /**
   * Execute the given process chain with a [LocalAgent] and return an object
   * that can be sent back to the remote peer
   * @param processChain the process chain to execute
   * @return the reply message (containing either results or an error message)
   */
  private suspend fun executeProcessChain(processChain: ProcessChain) = try {
    val la = LocalAgent()
    val results = la.execute(processChain)
    json {
      obj(
          "results" to JsonUtils.toJson(results)
      )
    }
  } catch (t: Throwable) {
    val message = if (t is LocalAgent.ExecutionException) {
      """
        ${t.message}

        Exit code: ${t.exitCode}

        ${t.lastOutput}
      """.trimIndent()
    } else {
      t.message
    }
    json {
      obj(
          "errorMessage" to message
      )
    }
  }
}
