import agent.LocalAgent
import agent.RemoteAgentRegistry
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.Shell
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.impl.NoStackTraceThrowable
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
import model.Version
import model.processchain.ProcessChain
import model.workflow.Workflow
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The JobManager's main API entry point
 * @author Michel Kraemer
 */
class JobManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(JobManager::class.java)
  }

  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var version: Version

  private lateinit var capabilities: Set<String>
  private var busy = false
  private var lastProcessChainSequence = -1L

  override suspend fun start() {
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    version = JsonUtils.mapper.readValue(javaClass.getResource("/version.json"))

    // deploy remote agent
    val agentEnabled = config.getBoolean(ConfigConstants.AGENT_ENABLED, true)
    if (agentEnabled) {
      // consume process chains and run a local agent for each of them
      val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId
      vertx.eventBus().consumer<JsonObject>(address, this::onAgentMessage)

      // register remote agent
      val rar = RemoteAgentRegistry(vertx)
      capabilities = config.getJsonArray(ConfigConstants.AGENT_CAPABILTIIES,
          JsonArray()).map { it as String }.toSet()
      rar.register(Main.agentId)

      log.info("Remote agent `${Main.agentId}' successfully deployed")
    } else {
      capabilities = emptySet()
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
          .setBodyLimit(config.getLong(ConfigConstants.HTTP_POST_MAX_SIZE, 1024 * 1024))

      router.get("/").handler(this::onGet)
      router.get("/workflows").handler(this::onGetWorkflows)
      router.get("/workflows/:id").handler(this::onGetWorkflowById)
      router.post("/workflows")
          .handler(bodyHandler)
          .handler(this::onPostWorkflow)
      router.get("/processchains").handler(this::onGetProcessChains)
      router.get("/processchains/:id").handler(this::onGetProcessChainById)

      server.requestHandler(router).listenAwait(port, host)

      log.info("JobManager deployed to http://$host:$port")
    }
  }

  /**
   * Handle a message that is sent to our agent
   */
  private fun onAgentMessage(msg: Message<JsonObject>) {
    try {
      val jsonObj: JsonObject = msg.body()
      val action = jsonObj.getString("action")
      when (action) {
        "inquire" -> onAgentInquire(msg)
        "allocate" -> onAgentAllocate(msg)
        "deallocate" -> onAgentDeallocate(msg)
        "process" -> onProcessChain(msg)
        else -> throw NoStackTraceThrowable("Unknown action `$action'")
      }
    } catch (e: Throwable) {
      msg.fail(400, e.message)
    }
  }

  /**
   * Handle an inquiry whether we are able to handle a process chain that
   * requires a given set of capabilities
   */
  private fun onAgentInquire(msg: Message<JsonObject>) {
    val available = if (busy) {
      false
    } else {
      // we are not busy - check if we have the required capabilities
      val arr = msg.body().getJsonArray("requiredCapabilities")
      val requiredCapabilities = arr.map { it as String }
      capabilities.containsAll(requiredCapabilities)
    }

    val reply = json {
      obj(
          "available" to available,
          "lastSequence" to lastProcessChainSequence
      )
    }
    msg.reply(reply)
  }

  /**
   * Handle an allocation message
   */
  private fun onAgentAllocate(msg: Message<JsonObject>) {
    if (busy) {
      msg.fail(503, "Agent is busy")
    } else {
      busy = true
      msg.reply("ACK")
    }
  }

  /**
   * Handle a deallocation message
   */
  private fun onAgentDeallocate(msg: Message<JsonObject>) {
    busy = false
    msg.reply("ACK")
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
      lastProcessChainSequence = jsonObj.getLong("sequence", -1L)

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
    val la = LocalAgent(vertx)
    val results = la.execute(processChain)
    json {
      obj(
          "results" to JsonUtils.toJson(results)
      )
    }
  } catch (t: Throwable) {
    val message = if (t is Shell.ExecutionException) {
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
    ctx.response()
        .putHeader("content-type", "application/json")
        .end(JsonUtils.toJson(version).encodePrettily())
  }

  /**
   * Amend submission JSON with additional information such as progress
   * @param submission the JSON object to amend
   */
  private suspend fun amendSubmission(submission: JsonObject) {
    val submissionId = submission.getString("id")
    val runningProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.RUNNING)
    val succeededProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.SUCCESS)
    val failedProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.ERROR)
    val totalProcessChains = submissionRegistry.countProcessChainsBySubmissionId(
        submissionId)
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
      val list = submissionRegistry.findSubmissions()
          .sortedWith(compareByDescending(nullsLast<Instant>()) { it.startTime })
          .map { submission ->
        JsonUtils.toJson(submission).also {
          it.remove("workflow")
          amendSubmission(it)
        }
      }
      val arr = JsonArray(list)
      ctx.response()
          .putHeader("content-type", "application/json")
          .end(arr.encode())
    }
  }

  /**
   * Get single workflow by ID
   * @param ctx the routing context
   */
  private fun onGetWorkflowById(ctx: RoutingContext) {
    launch {
      val id = ctx.pathParam("id")
      val submission = submissionRegistry.findSubmissionById(id)
      if (submission == null) {
        ctx.response()
            .setStatusCode(404)
            .end("There is no workflow with ID `$id'")
      } else {
        val json = JsonUtils.toJson(submission)
        amendSubmission(json)
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(json.encode())
      }
    }
  }

  /**
   * Execute a workflow
   * @param ctx the routing context
   */
  private fun onPostWorkflow(ctx: RoutingContext) {
    // parse workflow
    val workflowJson = try {
      ctx.bodyAsJson
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow JSON: " + e.message)
      return
    }

    val api = workflowJson.getValue("api")
    if ("3.0.0" != api) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow api version: $api")
      return
    }

    val workflow = try {
      JsonUtils.fromJson<Workflow>(workflowJson)
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow: " + e.message)
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

  private suspend fun amendProcessChain(processChain: JsonObject, submissionId: String) {
    processChain.put("submissionId", submissionId)

    val id = processChain.getString("id")

    val status = submissionRegistry.getProcessChainStatus(id)
    processChain.put("status", status.toString())

    if (status == SubmissionRegistry.ProcessChainStatus.SUCCESS) {
      val results = submissionRegistry.getProcessChainResults(id)
      if (results != null) {
        processChain.put("results", results)
      }
    } else if (status == SubmissionRegistry.ProcessChainStatus.ERROR) {
      val errorMessage = submissionRegistry.getProcessChainErrorMessage(id)
      if (errorMessage != null) {
        processChain.put("errorMessage", errorMessage)
      }
    }
  }

  /**
   * Get list of process chains
   * @param ctx the routing context
   */
  private fun onGetProcessChains(ctx: RoutingContext) {
    launch {
      var submissionIds = ctx.queryParam("submissionId") ?: emptyList()
      if (submissionIds.isEmpty()) {
          submissionIds = submissionRegistry.findSubmissions().map { it.id }
      }

      val list = submissionIds.flatMap { submissionId ->
        submissionRegistry.findProcessChainsBySubmissionId(submissionId).map { processChain ->
          JsonUtils.toJson(processChain).also {
            it.remove("executables")
            amendProcessChain(it, submissionId)
          }
        }
      }

      val arr = JsonArray(list)
      ctx.response()
          .putHeader("content-type", "application/json")
          .end(arr.encode())
    }
  }

  /**
   * Get single process chain by ID
   * @param ctx the routing context
   */
  private fun onGetProcessChainById(ctx: RoutingContext) {
    launch {
      val id = ctx.pathParam("id")
      val processChain = submissionRegistry.findProcessChainById(id)
      if (processChain == null) {
        ctx.response()
            .setStatusCode(404)
            .end("There is no process chain with ID `$id'")
      } else {
        val json = JsonUtils.toJson(processChain)
        val submissionId = submissionRegistry.getProcessChainSubmissionId(id)
        amendProcessChain(json, submissionId)
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(json.encode())
      }
    }
  }
}
