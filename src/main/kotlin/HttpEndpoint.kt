import AddressConstants.CONTROLLER_LOOKUP_NOW
import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.PROCESSCHAINS_ADDED
import AddressConstants.PROCESSCHAINS_ADDED_SIZE
import AddressConstants.PROCESSCHAIN_ENDTIME_CHANGED
import AddressConstants.PROCESSCHAIN_ERRORMESSAGE_CHANGED
import AddressConstants.PROCESSCHAIN_STARTTIME_CHANGED
import AddressConstants.PROCESSCHAIN_STATUS_CHANGED
import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_BUSY
import AddressConstants.REMOTE_AGENT_IDLE
import AddressConstants.REMOTE_AGENT_LEFT
import AddressConstants.SUBMISSION_ADDED
import AddressConstants.SUBMISSION_ENDTIME_CHANGED
import AddressConstants.SUBMISSION_ERRORMESSAGE_CHANGED
import AddressConstants.SUBMISSION_STARTTIME_CHANGED
import AddressConstants.SUBMISSION_STATUS_CHANGED
import agent.RemoteAgentRegistry
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.zafarkhaja.semver.expr.CompositeExpression.Helper.gte
import com.github.zafarkhaja.semver.expr.CompositeExpression.Helper.lte
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.lexer.Syntax
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import helper.JsonUtils
import helper.YamlUtils
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.vertx.MetricsHandler
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Submission
import model.Version
import model.workflow.Action
import model.workflow.ForEachAction
import model.workflow.StoreAction
import model.workflow.Workflow
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.StringWriter
import java.lang.ClassCastException
import java.lang.IllegalArgumentException
import kotlin.math.max
import com.github.zafarkhaja.semver.Version as SemVersion

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
        "servicesjs" to "/assets/services.js",
        "paginationjs" to "/assets/pagination.js",
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

  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var remoteAgentRegistry: RemoteAgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var basePath: String

  override suspend fun start() {
    metadataRegistry = MetadataRegistryFactory.create(vertx)
    remoteAgentRegistry = RemoteAgentRegistry(vertx)
    submissionRegistry = SubmissionRegistryFactory.create(vertx)

    val host = config.getString(ConfigConstants.HTTP_HOST, "localhost")
    val port = config.getInteger(ConfigConstants.HTTP_PORT, 8080)
    basePath = config.getString(ConfigConstants.HTTP_BASE_PATH, "").let {
      if (!it.startsWith("/")) {
        "/$it"
      } else {
        it
      }
    }.let {
      if (it.endsWith("/")) {
        it.substring(0, it.length - 1)
      } else {
        it
      }
    }

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

    router.get("/services")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetServices)

    router.get("/services/:id")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetServiceById)

    router.get("/processchains")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetProcessChains)

    router.get("/processchains/:id")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetProcessChainById)

    router.put("/processchains/:id")
        .handler(bodyHandler)
        .produces("application/json")
        .handler(this::onPutProcessChainById)

    router.get("/workflows")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetWorkflows)

    router.get("/workflows/:id")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetWorkflowById)

    router.put("/workflows/:id")
        .handler(bodyHandler)
        .produces("application/json")
        .handler(this::onPutWorkflowById)

    router.post("/workflows")
        .handler(bodyHandler)
        .handler(this::onPostWorkflow)

    router.route("/metrics")
        .handler(MetricsHandler())
    DefaultExports.initialize()

    // add a handler for assets that removes SHA256 checksums from filenames
    // and then forwards to a static handler
    router.get("/assets/*").handler { context ->
      val request = context.request()
      if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
        context.next()
      } else {
        val path = HttpUtils.removeDots(URIDecoder.decodeURIComponent(context.normalisedPath(), false))?.let {
          if (it.startsWith(basePath)) {
            it.substring(basePath.length)
          } else {
            it
          }
        }
        if (path == null) {
          log.warn("Invalid path: " + context.request().path())
          context.next()
        } else {
          val assetId = ASSET_IDS[path]
          if (assetId != null) {
            context.reroute(basePath + TRANSIENT_ASSETS[assetId])
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
            .setAddress(SUBMISSION_ADDED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_STARTTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_ENDTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_STATUS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_ERRORMESSAGE_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAINS_ADDED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAINS_ADDED_SIZE))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_STARTTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_ENDTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_STATUS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_ERRORMESSAGE_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_ADDED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_LEFT))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_BUSY))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_IDLE)))
    router.route("/eventbus/*").handler(sockJSHandler)

    val baseRouter = Router.router(vertx)
    baseRouter.mountSubRouter("$basePath/", router)
    server.requestHandler(baseRouter).listenAwait(port, host)

    log.info("HTTP endpoint deployed to http://$host:$port$basePath")
  }

  override suspend fun stop() {
    submissionRegistry.close()
  }

  /**
   * Renders an HTML template to the given HTTP response
   */
  private fun renderHtml(templateName: String, context: Map<String, Any?>,
      response: HttpServerResponse) {
    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .syntax(Syntax("$#", "#$", "$%", "%$", "$$", "$$", "#$", "$", "-", false))
        .build()
    val compiledTemplate = engine.getTemplate(templateName)
    val writer = StringWriter()
    compiledTemplate.evaluate(writer, context + mapOf(
        "assets" to ASSET_SHAS,
        "basePath" to basePath
    ))
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
      renderHtml("html/index.html", mapOf(
          "version" to VERSION
      ), ctx.response())
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
          REMOTE_AGENT_ADDRESS_PREFIX + it, msg) }.map { it.body() }

      val result = JsonArray(agents).encode()

      if (ctx.acceptableContentType == "text/html") {
        renderHtml("html/agents/index.html", mapOf(
            "agents" to result
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
            REMOTE_AGENT_ADDRESS_PREFIX + id, msg).body()

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
   * Get a list of all services
   * @param ctx the routing context
   */
  private fun onGetServices(ctx: RoutingContext) {
    launch {
      val services = metadataRegistry.findServices().map { JsonUtils.toJson(it) }
      val result = JsonArray(services).encode()

      if (ctx.acceptableContentType == "text/html") {
        renderHtml("html/services/index.html", mapOf(
            "services" to result
        ), ctx.response())
      } else {
        ctx.response()
            .putHeader("content-type", "application/json")
            .end(result)
      }
    }
  }

  /**
   * Get a single service by ID
   * @param ctx the routing context
   */
  private fun onGetServiceById(ctx: RoutingContext) {
    launch {
      val id = ctx.pathParam("id")
      val services = metadataRegistry.findServices()
      val service = services.find { it.id == id }

      if (service == null) {
        ctx.response()
            .setStatusCode(404)
            .end("There is no service with ID `$id'")
      } else {
        val serviceObj = JsonUtils.toJson(service)
        if (ctx.acceptableContentType == "text/html") {
          renderHtml("html/services/single.html", mapOf(
              "id" to id,
              "name" to service.name,
              "services" to JsonArray(serviceObj).encode()
          ), ctx.response())
        } else {
          ctx.response()
              .putHeader("content-type", "application/json")
              .end(serviceObj.encode())
        }
      }
    }
  }

  /**
   * Amend submission JSON with additional information such as progress
   * @param submission the JSON object to amend
   * @param includeDetails `true` if details such as submission results should
   * be included
   */
  private suspend fun amendSubmission(submission: JsonObject,
      includeDetails: Boolean = false) {
    val submissionId = submission.getString("id")
    val runningProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.RUNNING)
    val cancelledProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.CANCELLED)
    val succeededProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.SUCCESS)
    val failedProcessChains = submissionRegistry.countProcessChainsByStatus(
        submissionId, SubmissionRegistry.ProcessChainStatus.ERROR)
    val totalProcessChains = submissionRegistry.countProcessChainsBySubmissionId(
        submissionId)
    submission.put("runningProcessChains", runningProcessChains)
    submission.put("cancelledProcessChains", cancelledProcessChains)
    submission.put("succeededProcessChains", succeededProcessChains)
    submission.put("failedProcessChains", failedProcessChains)
    submission.put("totalProcessChains", totalProcessChains)

    if (includeDetails) {
      val strStatus = submission.getString("status")
      if (strStatus == Submission.Status.SUCCESS.toString()) {
        val results = submissionRegistry.getSubmissionResults(submissionId)
        if (results != null) {
          submission.put("results", results)
        }
      } else if (strStatus == Submission.Status.ERROR.toString()) {
        val errorMessage = submissionRegistry.getSubmissionErrorMessage(submissionId)
        if (errorMessage != null) {
          submission.put("errorMessage", errorMessage)
        }
      }
    }
  }

  /**
   * Get list of workflows
   * @param ctx the routing context
   */
  private fun onGetWorkflows(ctx: RoutingContext) {
    launch {
      val isHtml = ctx.acceptableContentType == "text/html"
      val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
      // TODO also use default size for json result
      val size = ctx.request().getParam("size")?.toIntOrNull() ?: if (isHtml) 10 else -1

      val total = submissionRegistry.countSubmissions()
      val submissions = submissionRegistry.findSubmissions(size, offset, -1)

      val list = submissions.map { submission ->
        // do not unnecessarily encode workflow to save time for large workflows
        val c = submission.copy(workflow = Workflow())

        JsonUtils.toJson(c).also {
          it.remove("workflow")
          amendSubmission(it)
        }
      }

      val encodedJson = JsonArray(list).encode()

      if (isHtml) {
        renderHtml("html/workflows/index.html", mapOf(
            "workflows" to encodedJson,
            "page" to mapOf(
                "size" to if (size < 0) total else size,
                "offset" to offset,
                "total" to total
            )
        ), ctx.response())
      } else {
        ctx.response()
            .putHeader("content-type", "application/json")
            .putHeader("x-page-size", size.toString())
            .putHeader("x-page-offset", offset.toString())
            .putHeader("x-page-total", total.toString())
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
        amendSubmission(json, true)
        if (ctx.acceptableContentType == "text/html") {
          renderHtml("html/workflows/single.html", mapOf(
              "id" to submission.id,
              "workflows" to JsonArray(json).encode()
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
   * Update a single workflow by ID
   * @param ctx the routing context
   */
  private fun onPutWorkflowById(ctx: RoutingContext) {
    val id = ctx.pathParam("id")
    val update = try {
      ctx.bodyAsJson
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid request body: " + e.message)
      return
    }

    val strStatus = try {
      update.getString("status")
    } catch (e: ClassCastException) {
      ctx.response()
          .setStatusCode(400)
          .end("`status' property must be a string")
      return
    }

    if (strStatus == null) {
      ctx.response()
          .setStatusCode(400)
          .end("Missing `status' property")
      return
    }

    val status = try {
      Submission.Status.valueOf(strStatus)
    } catch (e: IllegalArgumentException) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid `status' property")
      return
    }

    launch {
      val submission = submissionRegistry.findSubmissionById(id)
      if (submission == null) {
        ctx.response()
            .setStatusCode(404)
            .end("There is no workflow with ID `$id'")
      } else {
        if (submission.status != status && status == Submission.Status.CANCELLED) {
          // first, atomically cancel all process chains that are currently
          // registered but not running yet
          submissionRegistry.setAllProcessChainsStatus(id,
              SubmissionRegistry.ProcessChainStatus.REGISTERED,
              SubmissionRegistry.ProcessChainStatus.CANCELLED)

          // now cancel running process chains
          val pcIds = submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(
              id, SubmissionRegistry.ProcessChainStatus.RUNNING)
          // request cancellation (see also onPutProcessChainById())
          val cancelMsg = json {
            obj(
                "action" to "cancel"
            )
          }
          for (pcId in pcIds) {
            vertx.eventBus().send(LOCAL_AGENT_ADDRESS_PREFIX + pcId, cancelMsg)
          }

          // optimistically wait up to 2 seconds for the submission
          // to become cancelled
          for (i in 1..4) {
            delay(500)
            val updatedStatus = submissionRegistry.getSubmissionStatus(id)
            if (updatedStatus == Submission.Status.CANCELLED) {
              break
            }
          }
        }

        val updatedSubmission = submissionRegistry.findSubmissionById(id)!!
        val json = JsonUtils.toJson(updatedSubmission)
        json.remove("workflow")
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
    val workflowJson: Map<String, Any> = try {
      val str = ctx.bodyAsString.trim()
      if (str[0] == '{') {
        JsonUtils.mapper.readValue(str)
      } else {
        YamlUtils.mapper.readValue(str)
      }
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow JSON: " + e.message)
      return
    }

    val api = try {
      SemVersion.valueOf(workflowJson["api"].toString())
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow api version: " + e.message)
      return
    }
    if (!api.satisfies(gte("3.0.0").and(lte("3.3.0")))) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow api version: $api. Supported version range is [3.0.0, 3.3.0].")
      return
    }

    val workflow = try {
      JsonUtils.mapper.convertValue<Workflow>(workflowJson)
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow: " + e.message)
      return
    }

    if (containsStore(workflow.actions)) {
      log.warn("Store actions are deprecated and scheduled to be removed in " +
          "workflow API version 4.0.0. Use [OutputParameter.store] instead.")
    }

    // log first 100 lines of workflow
    val serializedWorkflow = JsonUtils.mapper.copy()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .writeValueAsString(workflow)
    val lines = serializedWorkflow.lineSequence().take(101).toList()
    if (lines.size <= 100) {
      log.info("Received workflow:\n" + lines.joinToString("\n"))
    } else {
      log.info("Received workflow (first 100 lines):\n" +
          lines.take(100).joinToString("\n") + "\n...")
    }

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
        vertx.eventBus().send(CONTROLLER_LOOKUP_NOW, null)
      } catch (e: Exception) {
        ctx.response()
            .setStatusCode(500)
            .end(e.message)
      }
    }
  }

  /**
   * Recursively checks if the given list of [actions] contains a [StoreAction]
   */
  private fun containsStore(actions: List<Action>): Boolean = actions.any {
    when (it) {
      is ForEachAction -> containsStore(it.actions)
      is StoreAction -> true
      else -> false
    }
  }

  private suspend fun amendProcessChain(processChain: JsonObject,
      submissionId: String, includeDetails: Boolean = false) {
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
        status == SubmissionRegistry.ProcessChainStatus.ERROR ||
        status == SubmissionRegistry.ProcessChainStatus.CANCELLED) {
      val endTime = submissionRegistry.getProcessChainEndTime(id)
      if (endTime != null) {
        processChain.put("endTime", endTime)
      }
    }

    if (includeDetails) {
      if (status == SubmissionRegistry.ProcessChainStatus.SUCCESS) {
        val results = submissionRegistry.getProcessChainResults(id)
        if (results != null) {
          processChain.put("results", results)
        }
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
      val isHtml = ctx.acceptableContentType == "text/html"
      val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
      // TODO also use default size for json result
      val size = ctx.request().getParam("size")?.toIntOrNull() ?: if (isHtml) 10 else -1

      val submissionId: String? = ctx.request().getParam("submissionId")
      val list = if (submissionId == null) {
        submissionRegistry.findProcessChains(size, offset, -1)
      } else {
        submissionRegistry.findProcessChainsBySubmissionId(submissionId, size, offset, -1)
            .map { Pair(it, submissionId) }
      }.map { p ->
        JsonUtils.toJson(p.first).also {
          it.remove("executables")
          // TODO breaking change: do not include details when we load all process chains
          amendProcessChain(it, p.second, true)
        }
      }

      val total = if (submissionId == null) {
        submissionRegistry.countProcessChains()
      } else {
        submissionRegistry.countProcessChainsBySubmissionId(submissionId)
      }

      val encodedJson = JsonArray(list).encode()

      if (isHtml) {
        renderHtml("html/processchains/index.html", mapOf(
            "submissionId" to submissionId,
            "processChains" to encodedJson,
            "page" to mapOf(
                "size" to if (size < 0) total else size,
                "offset" to offset,
                "total" to total
            )
        ), ctx.response())
      } else {
        ctx.response()
            .putHeader("content-type", "application/json")
            .putHeader("x-page-size", size.toString())
            .putHeader("x-page-offset", offset.toString())
            .putHeader("x-page-total", total.toString())
            .end(encodedJson)
      }
    }
  }

  /**
   * Get a single process chain by ID
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
        amendProcessChain(json, submissionId, true)
        if (ctx.acceptableContentType == "text/html") {
          renderHtml("html/processchains/single.html", mapOf(
              "id" to id,
              "processChains" to JsonArray(json).encode()
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
   * Update a single process chain by ID
   * @param ctx the routing context
   */
  private fun onPutProcessChainById(ctx: RoutingContext) {
    val id = ctx.pathParam("id")
    val update = try {
      ctx.bodyAsJson
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid request body: " + e.message)
      return
    }

    val strStatus = try {
      update.getString("status")
    } catch (e: ClassCastException) {
      ctx.response()
          .setStatusCode(400)
          .end("`status' property must be a string")
      return
    }

    if (strStatus == null) {
      ctx.response()
          .setStatusCode(400)
          .end("Missing `status' property")
      return
    }

    val status = try {
      SubmissionRegistry.ProcessChainStatus.valueOf(strStatus)
    } catch (e: IllegalArgumentException) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid `status' property")
      return
    }

    launch {
      val processChain = submissionRegistry.findProcessChainById(id)
      if (processChain == null) {
        ctx.response()
            .setStatusCode(404)
            .end("There is no process chain with ID `$id'")
      } else {
        if (status == SubmissionRegistry.ProcessChainStatus.CANCELLED) {
          val currentStatus = submissionRegistry.getProcessChainStatus(id)
          if (currentStatus == SubmissionRegistry.ProcessChainStatus.REGISTERED) {
            submissionRegistry.setProcessChainStatus(id, currentStatus, status)
          } else if (currentStatus == SubmissionRegistry.ProcessChainStatus.RUNNING) {
            // Ask local agent (running anywhere in the cluster) to cancel
            // the process chain. Its status should be set to CANCELLED by
            // the scheduler as soon as the local agent has aborted the
            // execution and the JobManager has sent this information back to
            // the remote agent.
            vertx.eventBus().send(LOCAL_AGENT_ADDRESS_PREFIX + id, json {
              obj(
                  "action" to "cancel"
              )
            })

            // optimistically wait up to 2 seconds for the process chain
            // to become cancelled
            for (i in 1..4) {
              delay(500)
              val updatedStatus = submissionRegistry.getProcessChainStatus(id)
              if (updatedStatus == SubmissionRegistry.ProcessChainStatus.CANCELLED) {
                break
              }
            }
          }
        }

        val updatedProcessChain = submissionRegistry.findProcessChainById(id)!!
        val json = JsonUtils.toJson(updatedProcessChain)
        json.remove("executables")
        val submissionId = submissionRegistry.getProcessChainSubmissionId(id)
        amendProcessChain(json, submissionId)

        ctx.response()
            .putHeader("content-type", "application/json")
            .end(json.encode())
      }
    }
  }
}
