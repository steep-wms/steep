import agent.RemoteAgentRegistry
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.lexer.Syntax
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.JsonUtils
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.http.impl.HttpUtils
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.impl.URIDecoder
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
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import model.Submission
import model.Version
import model.workflow.Workflow
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.StringWriter
import java.time.Instant

/**
 * The JobManager's main API entry point
 * @author Michel Kraemer
 */
class HttpEndpoint : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(HttpEndpoint::class.java)

    /**
     * A list of asset files that should have their SHA256 checksum encoded in
     * their filename so we can cache them for a very long time in the browser
     */
    private val TRANSIENT_ASSETS: Map<String, String> = mapOf(
        "indexcss" to "/assets/index.css",
        "agentsjs" to "/assets/agents.js",
        "processchainsjs" to "/assets/processchains.js",
        "workflowsjs" to "/assets/workflows.js"
    )

    /**
     * Calculate SHA256 checksum for all [TRANSIENT_ASSETS]
     */
    private val ASSET_SHAS = TRANSIENT_ASSETS.mapValues { (_, v) ->
      val sha = JobManager::class.java.getResourceAsStream(v)
          .use { DigestUtils.sha256Hex(it) }
      val ext = FilenameUtils.getExtension(v)
      val basename = FilenameUtils.removeExtension(v)
      "$basename.$sha.$ext"
    }

    /**
     * Reverse [ASSET_SHAS] so we can quickly get the asset ID
     */
    private val ASSET_IDS = ASSET_SHAS.map { (k, v) -> Pair(v, k) }.toMap()

    private val VERSION: Version = JsonUtils.mapper.readValue(
        HttpEndpoint::class.java.getResource("/version.json"))
  }

  private lateinit var remoteAgentRegistry: RemoteAgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry

  override suspend fun start() {
    remoteAgentRegistry = RemoteAgentRegistry(vertx)
    submissionRegistry = SubmissionRegistryFactory.create(vertx)

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

    router.get("/processchains")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetProcessChains)

    router.get("/processchains/:id").handler(this::onGetProcessChainById)

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

    // add a handler for assets that removes SHA256 checksums from filenames
    // and then forwards to a static handler
    router.get("/assets/*").handler { context ->
      val request = context.request()
      if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
        context.next()
      } else {
        val path = HttpUtils.removeDots(URIDecoder.decodeURIComponent(context.normalisedPath(), false))
        if (path == null) {
          log.warn("Invalid path: " + context.request().path())
          context.next()
        } else {
          val assetId = ASSET_IDS[path]
          if (assetId != null) {
            context.reroute(TRANSIENT_ASSETS[assetId])
          } else {
            context.next()
          }
        }
      }
    }

    // a static handler for assets
    router.get("/assets/*").handler(StaticHandler.create("assets")
        .setMaxAgeSeconds(60 * 60 * 24 * 365) /* one year */)

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
            .setAddress(AddressConstants.PROCESSCHAIN_STARTTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(AddressConstants.PROCESSCHAIN_ENDTIME_CHANGED))
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

    log.info("HTTP endpoint deployed to http://$host:$port")
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
      renderHtml("html/index.html", mapOf("version" to VERSION), ctx.response())
    } else {
      ctx.response()
          .putHeader("content-type", "application/json")
          .end(JsonUtils.toJson(VERSION).encodePrettily())
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
        renderHtml("html/agents/index.html", mapOf(
            "agents" to result,
            "assets" to ASSET_SHAS
        ), ctx.response())
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
              "agents" to JsonArray(agent).encode(),
              "assets" to ASSET_SHAS
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
            "processChains" to JsonObject(processChains).encode(),
            "assets" to ASSET_SHAS
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
              "processChains" to JsonObject(processChains),
              "assets" to ASSET_SHAS
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

    if (status != SubmissionRegistry.ProcessChainStatus.REGISTERED) {
      val startTime = submissionRegistry.getProcessChainStartTime(id)
      if (startTime != null) {
        processChain.put("startTime", startTime)
      }
    }

    if (status == SubmissionRegistry.ProcessChainStatus.SUCCESS ||
        status == SubmissionRegistry.ProcessChainStatus.ERROR) {
      val endTime = submissionRegistry.getProcessChainEndTime(id)
      if (endTime != null) {
        processChain.put("endTime", endTime)
      }
    }

    if (status == SubmissionRegistry.ProcessChainStatus.SUCCESS) {
      val results = submissionRegistry.getProcessChainResults(id)
      if (results != null) {
        processChain.put("results", results)
      }
    }

    if (status == SubmissionRegistry.ProcessChainStatus.ERROR) {
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
      val submissionIdsParam = ctx.queryParam("submissionId") ?: emptyList()
      val submissionIds = if (submissionIdsParam.isEmpty()) {
        submissionRegistry.findSubmissions().map { it.id }
      } else {
        submissionIdsParam
      }

      val list = submissionIds.flatMap { submissionId ->
        submissionRegistry.findProcessChainsBySubmissionId(submissionId).map { processChain ->
          JsonUtils.toJson(processChain).also {
            it.remove("executables")
            amendProcessChain(it, submissionId)
          }
        }
      }

      val encodedJson = JsonArray(list).encode()

      if (ctx.acceptableContentType == "text/html") {
        renderHtml("html/processchains/index.html", mapOf(
            "submissionIds" to JsonArray(submissionIdsParam),
            "processChains" to encodedJson,
            "assets" to ASSET_SHAS
        ), ctx.response())
      } else {
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(encodedJson)
      }
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
