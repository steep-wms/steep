import agent.LocalAgent
import agent.RemoteAgentRegistry
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.lexer.Syntax
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.Shell
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.impl.NoStackTraceThrowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.ResponseContentTypeHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.handler.sockjs.BridgeOptions
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.kotlin.core.eventbus.sendAwait
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.JsonArray
import io.vertx.kotlin.core.json.array
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
import java.io.StringWriter
import java.time.Duration
import java.time.Instant

/**
 * The JobManager's main API entry point
 * @author Michel Kraemer
 */
class JobManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(JobManager::class.java)
  }

  private lateinit var remoteAgentRegistry: RemoteAgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var version: Version

  private lateinit var capabilities: Set<String>
  private var busy: Instant? = null
  private lateinit var busyTimeout: Duration
  private var lastProcessChainSequence = -1L
  private lateinit var autoShutdownTimeout: Duration

  /**
   * The time when the last process chain finished executing
   */
  private var lastExecuteTime = Instant.now()

  override suspend fun start() {
    remoteAgentRegistry = RemoteAgentRegistry(vertx)
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    version = JsonUtils.mapper.readValue(javaClass.getResource("/version.json"))
    busyTimeout = Duration.ofSeconds(config.getLong(
        ConfigConstants.AGENT_BUSY_TIMEOUT, 60L))
    autoShutdownTimeout = Duration.ofMinutes(config.getLong(
        ConfigConstants.AGENT_AUTO_SHUTDOWN_TIMEOUT, 0))

    // deploy remote agent
    val agentEnabled = config.getBoolean(ConfigConstants.AGENT_ENABLED, true)
    if (agentEnabled) {
      // consume process chains and run a local agent for each of them
      val address = RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId
      vertx.eventBus().consumer<JsonObject>(address, this::onAgentMessage)

      // register remote agent
      capabilities = config.getJsonArray(ConfigConstants.AGENT_CAPABILTIIES,
          JsonArray()).map { it as String }.toSet()
      remoteAgentRegistry.register(Main.agentId)

      // setup automatic shutdown
      if (autoShutdownTimeout.toMinutes() > 0) {
        vertx.setPeriodic(1000 * 30) { checkAutoShutdown() }
      }

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

      router.route("/*").handler(ResponseContentTypeHandler.create())
      router.get("/")
          .produces("application/json")
          .produces("text/html")
          .handler(this::onGet)

      router.get("/agents")
          .produces("application/json")
          .produces("text/html")
          .handler(this::onGetAgents)

      router.get("/agents/:id")
          .produces("application/json")
          .produces("text/html")
          .handler(this::onGetAgentById)

      router.get("/workflows")
          .produces("application/json")
          .produces("text/html")
          .handler(this::onGetWorkflows)

      router.get("/workflows/:id")
          .produces("application/json")
          .produces("text/html")
          .handler(this::onGetWorkflowById)

      router.post("/workflows")
          .handler(bodyHandler)
          .handler(this::onPostWorkflow)

      router.get("/processchains").handler(this::onGetProcessChains)
      router.get("/processchains/:id").handler(this::onGetProcessChainById)

      router.get("/assets/*").handler(StaticHandler.create("assets"))

      val sockJSHandler = SockJSHandler.create(vertx)
      sockJSHandler.bridge(BridgeOptions()
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.SUBMISSION_ADDED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.SUBMISSION_STARTTIME_CHANGED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.SUBMISSION_ENDTIME_CHANGED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.SUBMISSION_STATUS_CHANGED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.PROCESSCHAINS_ADDED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.PROCESSCHAIN_STATUS_CHANGED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.REMOTE_AGENT_ADDED))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.REMOTE_AGENT_LEFT))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.REMOTE_AGENT_BUSY))
          .addOutboundPermitted(PermittedOptions()
              .setAddress(AddressConstants.REMOTE_AGENT_IDLE)))
      router.route("/eventbus/*").handler(sockJSHandler)

      server.requestHandler(router).listenAwait(port, host)

      log.info("JobManager deployed to http://$host:$port")
    }
  }

  /**
   * Checks if the agent has been idle for more than [autoShutdownTimeout]
   * minutes and, if so, shuts down the Vert.x instance.
   */
  private fun checkAutoShutdown() {
    if (!isBusy() && lastExecuteTime.isBefore(Instant.now().minus(autoShutdownTimeout))) {
      log.info("Agent has been idle for more than ${autoShutdownTimeout.toMinutes()} " +
          "minutes. Shutting down ...")
      vertx.close()
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
        "info" -> onAgentInfo(msg)
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
   * Returns `true` if the agent is busy
   */
  private fun isBusy(): Boolean {
    val timedOut = busy?.isBefore(Instant.now().minus(busyTimeout)) ?: return false
    if (timedOut) {
      markBusy(false)
      log.info("Idle agent `${Main.agentId}' was automatically marked as available again")
      return false
    }
    return true
  }

  /**
   * Marks this agent as busy or not busy
   */
  private fun markBusy(busy: Boolean = true) {
    if (busy) {
      if (this.busy == null) {
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_BUSY,
            RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId)
      }
      this.busy = Instant.now()
    } else {
      if (this.busy != null) {
        vertx.eventBus().publish(AddressConstants.REMOTE_AGENT_IDLE,
            RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + Main.agentId)
      }
      this.busy = null
    }
  }

  /**
   * Return information about this agent
   */
  private fun onAgentInfo(msg: Message<JsonObject>) {
    val reply = json {
      obj(
          "id" to Main.agentId,
          "available" to !isBusy(),
          "capabilities" to array(*capabilities.toTypedArray())
      )
    }
    msg.reply(reply)
  }

  /**
   * Handle an inquiry whether we are able to handle a process chain that
   * requires a given set of capabilities
   */
  private fun onAgentInquire(msg: Message<JsonObject>) {
    val available = if (isBusy()) {
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
    if (isBusy()) {
      msg.fail(503, "Agent is busy")
    } else {
      markBusy()
      msg.reply("ACK")
    }
  }

  /**
   * Handle a deallocation message
   */
  private fun onAgentDeallocate(msg: Message<JsonObject>) {
    markBusy(false)
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

      markBusy()
      val busyTimer = vertx.setPeriodic(busyTimeout.toMillis() / 2) {
        markBusy()
      }

      // run the local agent and return its results
      launch {
        try {
          val answer = executeProcessChain(processChain)
          vertx.eventBus().send(replyAddress, answer)
        } finally {
          lastExecuteTime = Instant.now()
          vertx.cancelTimer(busyTimer)
        }
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
   * Renders an HTML template to the given HTTP response
   */
  private fun renderHtml(templateName: String, context: Map<String, Any>,
      response: HttpServerResponse) {
    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .syntax(Syntax("$#", "#$", "$%", "%$", "$$", "$$", "#$", "$", "-", false))
        .build()
    val compiledTemplate = engine.getTemplate(templateName)
    val writer = StringWriter()
    compiledTemplate.evaluate(writer, context)
    response
        .putHeader("content-type", "text/html")
        .putHeader("cache-control", "no-cache, no-store, must-revalidate")
        .putHeader("expires", "0")
        .end(writer.toString())
  }

  /**
   * Get information about the JobManager
   * @param ctx the routing context
   */
  private fun onGet(ctx: RoutingContext) {
    if (ctx.acceptableContentType == "text/html") {
      renderHtml("html/index.html", mapOf("version" to version), ctx.response())
    } else {
      ctx.response()
          .putHeader("content-type", "application/json")
          .end(JsonUtils.toJson(version).encodePrettily())
    }
  }

  /**
   * Get a list of all agents
   * @param ctx the routing context
   */
  private fun onGetAgents(ctx: RoutingContext) {
    launch {
      val agentIds = remoteAgentRegistry.getAgentIds()

      val msg = json {
        obj(
            "action" to "info"
        )
      }
      val agents = agentIds.map { vertx.eventBus().sendAwait<JsonObject>(
          RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + it, msg) }.map { it.body() }

      val result = JsonArray(agents).encode()

      if (ctx.acceptableContentType == "text/html") {
        renderHtml("html/agents/index.html", mapOf("agents" to result), ctx.response())
      } else {
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(result)
      }
    }
  }

  /**
   * Get a single agent by ID
   * @param ctx the routing context
   */
  private fun onGetAgentById(ctx: RoutingContext) {
    launch {
      val id = ctx.pathParam("id")
      val msg = json {
        obj(
            "action" to "info"
        )
      }

      try {
        val agent = vertx.eventBus().sendAwait<JsonObject>(
            RemoteAgentRegistry.AGENT_ADDRESS_PREFIX + id, msg).body()

        if (ctx.acceptableContentType == "text/html") {
          renderHtml("html/agents/single.html", mapOf(
              "id" to id,
              "agents" to JsonArray(agent).encode()
          ), ctx.response())
        } else {
          ctx.response()
              .putHeader("content-type", "application/json")
              .end(agent.encode())
        }
      } catch (e: ReplyException) {
        if (e.failureType() === ReplyFailure.NO_HANDLERS) {
          ctx.response()
              .setStatusCode(404)
              .end("There is no agent with ID `$id'")
        } else {
          log.error("Could not get info about agent `$id'", e)
          ctx.response()
              .setStatusCode(500)
              .end()
        }
      }
    }
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
   * Loads all process chains for the given submission and converts them to
   * JSON objects suitable for our HTML templates.
   */
  private suspend fun getProcessChainsJsonForSubmission(submissionId: String): List<Pair<String, JsonObject>> {
    return submissionRegistry.findProcessChainStatusesBySubmissionId(submissionId).map { (id, status) ->
      id to json {
        obj(
            "submissionId" to submissionId,
            "status" to status.toString()
        )
      }
    }
  }

  /**
   * Get list of workflows
   * @param ctx the routing context
   */
  private fun onGetWorkflows(ctx: RoutingContext) {
    launch {
      val submissions = submissionRegistry.findSubmissions()
          .sortedWith(compareByDescending(nullsLast<Instant>()) { it.startTime })
      val list = submissions.map { submission ->
        // do not unnecessarily encode workflow to save time for large workflows
        val c = submission.copy(workflow = Workflow())

        JsonUtils.toJson(c).also {
          it.remove("workflow")
          amendSubmission(it)
        }
      }

      val encodedJson = JsonArray(list).encode()

      if (ctx.acceptableContentType == "text/html") {
        val processChains = submissions.flatMap { getProcessChainsJsonForSubmission(it.id) }.toMap()
        renderHtml("html/workflows/index.html", mapOf(
            "workflows" to encodedJson,
            "processChains" to JsonObject(processChains).encode()
        ), ctx.response())
      } else {
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(encodedJson)
      }
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
        if (ctx.acceptableContentType == "text/html") {
          val processChains = getProcessChainsJsonForSubmission(submission.id).toMap()
          renderHtml("html/workflows/single.html", mapOf(
              "id" to submission.id,
              "workflows" to JsonArray(json).encode(),
              "processChains" to JsonObject(processChains)
          ), ctx.response())
        } else {
          ctx.response()
              .putHeader("content-type", "application/json")
              .end(json.encode())
        }
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
