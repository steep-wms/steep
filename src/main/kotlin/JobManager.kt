import agent.LocalAgent
import agent.RemoteAgentRegistry
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.JsonUtils
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.get
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import model.Submission
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.slf4j.LoggerFactory

/**
 * The JobManager's main API entry point
 * @author Michel Kraemer
 */
class JobManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(JobManager::class.java)
  }

  private lateinit var submissionRegistry: SubmissionRegistry

  override suspend fun start() {
    submissionRegistry = SubmissionRegistryFactory.create(vertx)

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

      val bodyHandler = BodyHandler.create()
          .setHandleFileUploads(false)
          .setBodyLimit(config.getLong(ConfigConstants.HTTP_POST_MAX_SIZE))

      router.get("/").handler(this::onGet)
      router.get("/workflows").handler(this::onGetWorkflows)
      router.get("/workflows/:id").handler(this::onGetWorkflowById)
      router.post("/workflows")
          .handler(bodyHandler)
          .handler(this::onPostWorkflow)

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

  /**
   * Get information about the JobManager
   * @param ctx the routing context
   */
  private fun onGet(ctx: RoutingContext) {
    val response = json {
      obj(
          "version" to javaClass.getResource("/version.dat").readText()
      )
    }
    ctx.response().end(response.encodePrettily())
  }

  /**
   * Amend submission JSON with additional information such as progress
   * @param submission the JSON object to amend
   */
  private suspend fun amendSubmission(submission: JsonObject) {
    var runningProcessChains = 0L
    var succeededProcessChains = 0L
    var failedProcessChains = 0L
    val processChains = submissionRegistry.findProcessChainsBySubmissionId(
        submission.getString("id"))
    val totalProcessChains = processChains.size
    processChains.forEach { processChain ->
      val status = submissionRegistry.getProcessChainStatus(processChain.id)
      when (status) {
        SubmissionRegistry.ProcessChainStatus.REGISTERED -> {}
        SubmissionRegistry.ProcessChainStatus.RUNNING -> runningProcessChains++
        SubmissionRegistry.ProcessChainStatus.SUCCESS -> succeededProcessChains++
        SubmissionRegistry.ProcessChainStatus.ERROR -> failedProcessChains++
      }
    }

    submission.put("runningProcessChains", runningProcessChains)
    submission.put("succeededProcessChains", succeededProcessChains)
    submission.put("failedProcessChains", failedProcessChains)
    submission.put("totalProcessChains", totalProcessChains)
  }

  /**
   * Get list of workflows
   * @param ctx the routing context
   */
  private fun onGetWorkflows(ctx: RoutingContext) {
    launch {
      val list = submissionRegistry.findSubmissions().map { submission ->
        JsonUtils.toJson(submission).also {
          it.remove("workflow")
          amendSubmission(it)
        }
      }
      val arr = JsonArray(list)
      ctx.response().end(arr.encode())
    }
  }

  /**
   * Get single workflow by name
   * @param ctx the routing context
   */
  private fun onGetWorkflowById(ctx: RoutingContext) {
    launch {
      val id = ctx.pathParam("id")
      val submission = submissionRegistry.findSubmissionById(id)
      if (submission == null) {
        ctx.response()
            .setStatusCode(404)
            .end("There is no workflow with the ID `$id'")
      } else {
        val json = JsonUtils.toJson(submission)
        amendSubmission(json)
        ctx.response().end(json.encode())
      }
    }
  }

  /**
   * Execute a workflow
   * @param ctx the routing context
   */
  private fun onPostWorkflow(ctx: RoutingContext) {
    // parse workflow
    val workflow = try {
      JsonUtils.mapper.readValue<Workflow>(ctx.bodyAsString)
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow JSON: " + e.message)
      return
    }

    log.info("Received workflow:\n" + JsonUtils.mapper.copy()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(workflow))

    // store submission in registry
    val submission = Submission(workflow = workflow)
    launch {
      try {
        submissionRegistry.addSubmission(submission)
        ctx.response()
            .setStatusCode(202)
            .putHeader("content-type", "application/json")
            .end(JsonUtils.mapper.writeValueAsString(submission))

        // notify controller to speed up lookup process
        vertx.eventBus().send(AddressConstants.CONTROLLER_LOOKUP_NOW, null)
      } catch (e: Exception) {
        ctx.response()
            .setStatusCode(500)
            .end(e.message)
      }
    }
  }
}
