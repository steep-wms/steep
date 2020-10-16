import AddressConstants.CONTROLLER_LOOKUP_NOW
import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.PROCESSCHAINS_ADDED
import AddressConstants.PROCESSCHAINS_ADDED_SIZE
import AddressConstants.PROCESSCHAIN_ALL_STATUS_CHANGED
import AddressConstants.PROCESSCHAIN_ENDTIME_CHANGED
import AddressConstants.PROCESSCHAIN_ERRORMESSAGE_CHANGED
import AddressConstants.PROCESSCHAIN_PROGRESS_CHANGED
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
import AddressConstants.VM_ADDED
import AddressConstants.VM_AGENTJOINTIME_CHANGED
import AddressConstants.VM_CREATIONTIME_CHANGED
import AddressConstants.VM_DESTRUCTIONTIME_CHANGED
import AddressConstants.VM_EXTERNALID_CHANGED
import AddressConstants.VM_IPADDRESS_CHANGED
import AddressConstants.VM_REASON_CHANGED
import AddressConstants.VM_STATUS_CHANGED
import agent.AgentRegistry
import agent.AgentRegistryFactory
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.zafarkhaja.semver.expr.CompositeExpression.Helper.gte
import com.github.zafarkhaja.semver.expr.CompositeExpression.Helper.lte
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import db.VMRegistry
import db.VMRegistryFactory
import db.migration.removeExecuteActionParameters
import db.migration.removeStoreActions
import helper.JsonUtils
import helper.WorkflowValidator
import helper.YamlUtils
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.vertx.MetricsHandler
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.http.impl.HttpUtils
import io.vertx.core.http.impl.MimeMapping
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.net.impl.URIDecoder
import io.vertx.ext.bridge.PermittedOptions
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.ResponseContentTypeHandler
import io.vertx.ext.web.handler.sockjs.SockJSBridgeOptions
import io.vertx.ext.web.handler.sockjs.SockJSHandler
import io.vertx.ext.web.impl.ParsableMIMEValue
import io.vertx.kotlin.core.eventbus.requestAwait
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Submission
import model.Version
import model.cloud.VM
import model.workflow.Workflow
import org.apache.commons.text.WordUtils
import org.slf4j.LoggerFactory
import kotlin.math.max
import com.github.zafarkhaja.semver.Version as SemVersion

/**
 * Steep's main API entry point
 * @author Michel Kraemer
 */
class HttpEndpoint : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(HttpEndpoint::class.java)

    private val VERSION: Version = JsonUtils.mapper.readValue(
        HttpEndpoint::class.java.getResource("/version.json"))

    private val JSON = ParsableMIMEValue("application/json")
    private val HTML = ParsableMIMEValue("text/html")
  }

  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var agentRegistry: AgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var vmRegistry: VMRegistry
  private lateinit var basePath: String

  override suspend fun start() {
    metadataRegistry = MetadataRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)
    submissionRegistry = SubmissionRegistryFactory.create(vertx)
    vmRegistry = VMRegistryFactory.create(vertx)

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

    val corsEnable = config.getBoolean(ConfigConstants.HTTP_CORS_ENABLE, false)
    if (corsEnable) {
      router.route().handler(createCorsHandler())
    }

    router.route("/*").handler(ResponseContentTypeHandler.create())

    router.get("/")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGet)

    router.get("/agents")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetAgents)

    router.get("/agents/:id/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetAgentById)

    router.get("/new")
        .produces("text/html")
        .handler(this::onNew)

    router.get("/new/workflow")
        .produces("text/html")
        .handler(this::onNewWorkflow)

    router.get("/services")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetServices)

    router.get("/services/:id/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetServiceById)

    router.get("/processchains")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetProcessChains)

    router.get("/processchains/:id/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetProcessChainById)

    router.put("/processchains/:id/?")
        .handler(bodyHandler)
        .produces("application/json")
        .handler(this::onPutProcessChainById)

    router.get("/vms")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetVMs)

    router.get("/vms/:id/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetVMById)

    router.get("/workflows")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetWorkflows)

    router.get("/workflows/:id/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetWorkflowById)

    router.put("/workflows/:id/?")
        .handler(bodyHandler)
        .produces("application/json")
        .handler(this::onPutWorkflowById)

    router.post("/workflows")
        .handler(bodyHandler)
        .handler(this::onPostWorkflow)

    router.route("/metrics")
        .handler(MetricsHandler())
    DefaultExports.initialize()

    // a static handler that replaces placeholders in assets
    val placeholderHandler = { replaceFavicons: Boolean -> { context: RoutingContext ->
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
          renderAsset("ui$path", context.response(), replaceFavicons)
        }
      }
    }}
    router.get("/_next/*").handler(placeholderHandler(false))
    router.get("/favicons/*").handler(placeholderHandler(true))

    val sockJSHandler = SockJSHandler.create(vertx)
    sockJSHandler.bridge(SockJSBridgeOptions()
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
            .setAddress(PROCESSCHAIN_ALL_STATUS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_ERRORMESSAGE_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_PROGRESS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_ADDED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_LEFT))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_BUSY))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(REMOTE_AGENT_IDLE))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_ADDED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_CREATIONTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_AGENTJOINTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_DESTRUCTIONTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_STATUS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_EXTERNALID_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_IPADDRESS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VM_REASON_CHANGED)))
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
   * Create and configure a [CorsHandler]
   */
  private fun createCorsHandler(): CorsHandler {
    val allowedOrigin: String = config.getString(
        ConfigConstants.HTTP_CORS_ALLOW_ORIGIN, "$.") // match nothing by default
    val corsHandler = CorsHandler.create(allowedOrigin)

    // configure whether Access-Control-Allow-Credentials should be returned
    if (config.getBoolean(ConfigConstants.HTTP_CORS_ALLOW_CREDENTIALS, false)) {
      corsHandler.allowCredentials(true)
    }

    // configured allowed headers
    val allowHeaders = config.getValue(ConfigConstants.HTTP_CORS_ALLOW_HEADERS)
    when {
      allowHeaders is String -> {
        corsHandler.allowedHeader(allowHeaders)
      }
      allowHeaders is JsonArray -> {
        corsHandler.allowedHeaders(allowHeaders.map { it as String }.toSet())
      }
      allowHeaders != null -> {
        throw IllegalArgumentException(ConfigConstants.HTTP_CORS_ALLOW_HEADERS +
            " must either be a string or an array.")
      }
    }

    // configured allowed methods
    val allowMethods = config.getValue(ConfigConstants.HTTP_CORS_ALLOW_METHODS)
    when {
      allowMethods is String -> {
        corsHandler.allowedMethod(HttpMethod.valueOf(allowMethods))
      }
      allowMethods is JsonArray -> {
        corsHandler.allowedMethods(allowMethods.map { it as String}
            .map(HttpMethod::valueOf).toSet())
      }
      allowMethods != null -> {
        throw IllegalArgumentException(ConfigConstants.HTTP_CORS_ALLOW_METHODS +
            " must either be a string or an array.")
      }
    }

    // configured exposed headers
    val exposeHeaders = config.getValue(ConfigConstants.HTTP_CORS_EXPOSE_HEADERS)
    when {
      exposeHeaders is String -> {
        corsHandler.exposedHeader(exposeHeaders)
      }
      exposeHeaders is JsonArray -> {
        corsHandler.exposedHeaders(exposeHeaders.map { it as String }.toSet())
      }
      exposeHeaders != null -> {
        throw IllegalArgumentException(ConfigConstants.HTTP_CORS_EXPOSE_HEADERS +
            " must either be a string or an array.")
      }
    }

    // configure max age in seconds
    val maxAge = config.getInteger(ConfigConstants.HTTP_CORS_MAX_AGE, -1)
    corsHandler.maxAgeSeconds(maxAge)

    return corsHandler
  }

  /**
   * Renders an error to the client. Automatically sets correct content type.
   */
  private fun renderError(ctx: RoutingContext, statusCode: Int,
      message: String? = null) {
    val response = ctx.response()
    response.statusCode = statusCode
    if (message != null) {
      response.putHeader("content-type", "text/plain")
      response.end(message)
    } else {
      response.end()
    }
  }

  /**
   * Renders an asset to the given HTTP response. Replaces placeholders.
   */
  private fun renderAsset(name: String, response: HttpServerResponse,
      replaceFavicons: Boolean = false) {
    val url = this.javaClass.getResource(name)
    if (url == null) {
      response
          .setStatusCode(404)
          .end()
      return
    }

    val ext = name.substring(name.lastIndexOf('.') + 1)
    val contentType = MimeMapping.getMimeTypeForExtension(ext)

    val result = if (contentType == null || !contentType.startsWith("image/")) {
      val text = url.readText()
          .replace("/\$\$MYBASEPATH\$\$", basePath)
          .replace("/\$\$MYBASEURL\$\$", basePath).let {
            if (replaceFavicons) {
              it.replace("\"/favicons/", "\"$basePath/favicons/")
            } else {
              it
            }
          }
      Buffer.buffer(text)
    } else {
      Buffer.buffer(url.readBytes())
    }

    if (contentType != null) {
      response.putHeader("content-type", contentType)
    }

    if (contentType == "text/html") {
      response
          .putHeader("cache-control", "no-cache, no-store, must-revalidate")
          .putHeader("expires", "0")
    } else {
      response
          .putHeader("cache-control", "public, max-age=" + (60 * 60 * 24 * 365)) // one year
    }

    response.end(result)
  }

  /**
   * Check if the client prefers an HTML response
   */
  private fun prefersHtml(ctx: RoutingContext): Boolean {
    // ctx.parsedHeaders() contains the accepted mime types in
    // their preferred order
    for (header in ctx.parsedHeaders().accept()) {
      if (JSON.isMatchedBy(header)) {
        return false
      }
      if (HTML.isMatchedBy(header)) {
        return true
      }
    }
    return false
  }

  /**
   * Get information about Steep
   * @param ctx the routing context
   */
  private fun onGet(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/index.html", ctx.response())
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
    if (prefersHtml(ctx)) {
      renderAsset("ui/agents/index.html", ctx.response())
    } else {
      launch {
        val agentIds = agentRegistry.getAgentIds()

        val msg = json {
          obj(
              "action" to "info"
          )
        }
        val agents = agentIds.map { vertx.eventBus().requestAwait<JsonObject>(
            REMOTE_AGENT_ADDRESS_PREFIX + it, msg) }.map { it.body() }

        val result = JsonArray(agents).encode()

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
    if (prefersHtml(ctx)) {
      renderAsset("ui/agents/[id].html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val msg = json {
          obj(
              "action" to "info"
          )
        }

        try {
          val agent = vertx.eventBus().requestAwait<JsonObject>(
              REMOTE_AGENT_ADDRESS_PREFIX + id, msg).body()
          ctx.response()
              .putHeader("content-type", "application/json")
              .end(agent.encode())
        } catch (e: ReplyException) {
          if (e.failureType() === ReplyFailure.NO_HANDLERS) {
            renderError(ctx, 404, "There is no agent with ID `$id'")
          } else {
            log.error("Could not get info about agent `$id'", e)
            renderError(ctx, 500)
          }
        }
      }
    }
  }

  /**
   * Redirect to the "new workflow page
   * @param ctx the routing context
   */
  private fun onNew(ctx: RoutingContext) {
    var uri = ctx.request().absoluteURI()
    if (!uri.endsWith("/")) {
      uri += "/"
    }
    uri += "workflow"
    ctx.response().setStatusCode(301).putHeader("Location", uri).end()
  }

  /**
   * Get the "new workflow" page
   * @param ctx the routing context
   */
  private fun onNewWorkflow(ctx: RoutingContext) {
    renderAsset("ui/new/workflow/index.html", ctx.response())
  }

  /**
   * Get a list of all services
   * @param ctx the routing context
   */
  private fun onGetServices(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/services/index.html", ctx.response())
    } else {
      launch {
        val services = metadataRegistry.findServices().map { JsonUtils.toJson(it) }
        val result = JsonArray(services).encode()
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
    if (prefersHtml(ctx)) {
      renderAsset("ui/services/[id].html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val services = metadataRegistry.findServices()
        val service = services.find { it.id == id }

        if (service == null) {
          renderError(ctx, 404, "There is no service with ID `$id'")
        } else {
          val serviceObj = JsonUtils.toJson(service)
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
    val runningProcessChains = submissionRegistry.countProcessChains(
        submissionId, SubmissionRegistry.ProcessChainStatus.RUNNING)
    val cancelledProcessChains = submissionRegistry.countProcessChains(
        submissionId, SubmissionRegistry.ProcessChainStatus.CANCELLED)
    val succeededProcessChains = submissionRegistry.countProcessChains(
        submissionId, SubmissionRegistry.ProcessChainStatus.SUCCESS)
    val failedProcessChains = submissionRegistry.countProcessChains(
        submissionId, SubmissionRegistry.ProcessChainStatus.ERROR)
    val totalProcessChains = submissionRegistry.countProcessChains(submissionId)
    submission.put("runningProcessChains", runningProcessChains)
    submission.put("cancelledProcessChains", cancelledProcessChains)
    submission.put("succeededProcessChains", succeededProcessChains)
    submission.put("failedProcessChains", failedProcessChains)
    submission.put("totalProcessChains", totalProcessChains)

    if (includeDetails) {
      val strStatus = submission.getString("status")
      if (strStatus == Submission.Status.SUCCESS.toString() ||
          strStatus == Submission.Status.PARTIAL_SUCCESS.toString()) {
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
    if (prefersHtml(ctx)) {
      renderAsset("ui/workflows/index.html", ctx.response())
    } else {
      launch {
        val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
        val size = ctx.request().getParam("size")?.toIntOrNull() ?: 10

        val status = ctx.request().getParam("status")?.let {
          try {
            Submission.Status.valueOf(it)
          } catch (e: IllegalArgumentException) {
            renderError(ctx, 400, "Invalid status: $it")
            return@launch
          }
        }

        val total = submissionRegistry.countSubmissions(status)
        val submissions = submissionRegistry.findSubmissions(status, size, offset, -1)

        val list = submissions.map { submission ->
          val reqCaps = submission.collectRequiredCapabilities(
              metadataRegistry.findServices())

          // do not unnecessarily encode workflow to save time for large workflows
          val c = submission.copy(workflow = Workflow())

          JsonUtils.toJson(c).also {
            it.remove("workflow")
            amendSubmission(it)
            it.put("requiredCapabilities", jsonArrayOf(*(reqCaps.toTypedArray())))
          }
        }

        val encodedJson = JsonArray(list).encode()
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
    if (prefersHtml(ctx)) {
      renderAsset("ui/workflows/[id].html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val submission = submissionRegistry.findSubmissionById(id)
        if (submission == null) {
          renderError(ctx, 404, "There is no workflow with ID `$id'")
        } else {
          val json = JsonUtils.toJson(submission)
          val reqCaps = submission.collectRequiredCapabilities(
              metadataRegistry.findServices())

          amendSubmission(json, true)
          json.put("requiredCapabilities", jsonArrayOf(*(reqCaps.toTypedArray())))

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
      renderError(ctx, 400, "Invalid request body: " + e.message)
      return
    }

    if (update == null) {
      renderError(ctx, 400, "Missing request body")
      return
    }

    val strStatus = try {
      update.getString("status")
    } catch (e: ClassCastException) {
      renderError(ctx, 400, "`status' property must be a string")
      return
    }

    if (strStatus == null) {
      renderError(ctx, 400, "Missing `status' property")
      return
    }

    val status = try {
      Submission.Status.valueOf(strStatus)
    } catch (e: IllegalArgumentException) {
      renderError(ctx, 400, "Invalid `status' property")
      return
    }

    launch {
      val submission = submissionRegistry.findSubmissionById(id)
      if (submission == null) {
        renderError(ctx, 404, "There is no workflow with ID `$id'")
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
      renderError(ctx, 400, "Invalid workflow JSON: " + e.message)
      return
    }

    val api = try {
      SemVersion.valueOf(workflowJson["api"].toString())
    } catch (e: Exception) {
      renderError(ctx, 400, "Invalid workflow api version: " + e.message)
      return
    }
    if (!api.satisfies(gte("3.0.0").and(lte("4.2.0")))) {
      renderError(ctx, 400, "Invalid workflow api version: $api. " +
          "Supported version range is [3.0.0, 4.2.0].")
      return
    }

    // remove incompatible store action
    if (removeStoreActions(workflowJson)) {
      log.warn("Found a store action in the posted workflow. Such " +
          "actions were removed in workflow API version 4.0.0. The " +
          "action will be removed from the workflow. Use the `store' " +
          "flag on `output' parameters instead.")
    }

    // remove deprecated action parameters
    if (removeExecuteActionParameters(workflowJson)) {
      log.warn("Found `parameters' in an execute action. Parameters " +
          "are unnecessary and will be removed in workflow API " +
          "version 5.0.0. Use `inputs' instead. The posted workflow " +
          "will now be modified automatically and the parameters will " +
          "be merged into the action's inputs.")
    }

    val workflow = try {
      JsonUtils.mapper.convertValue<Workflow>(workflowJson)
    } catch (e: Exception) {
      renderError(ctx, 400, "Invalid workflow: " + e.message)
      return
    }

    // check workflow for common mistakes
    val validationResults = WorkflowValidator.validate(workflow)
    if (validationResults.isNotEmpty()) {
      renderError(ctx, 400, "Invalid workflow:\n\n" + validationResults.joinToString("\n\n") {
            "- ${WordUtils.wrap(it.message, 80, "\n  ", true)}\n\n  " +
                WordUtils.wrap(it.details, 80, "\n  ", true)
          })
      return
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
        renderError(ctx, 500, e.message)
      }
    }
  }

  /**
   * Get list of VMs
   * @param ctx the routing context
   */
  private fun onGetVMs(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/vms/index.html", ctx.response())
    } else {
      launch {
        val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
        val size = ctx.request().getParam("size")?.toIntOrNull() ?: 10

        val status = ctx.request().getParam("status")?.let {
          try {
            VM.Status.valueOf(it)
          } catch (e: IllegalArgumentException) {
            renderError(ctx, 400, "Invalid status: $it")
            return@launch
          }
        }

        val list = vmRegistry.findVMs(status, size, offset, -1).map { JsonUtils.toJson(it) }
        val total = vmRegistry.countVMs(status)
        val encodedJson = JsonArray(list).encode()

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
   * Get a single VM by ID
   * @param ctx the routing context
   */
  private fun onGetVMById(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/vms/[id].html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val vm = vmRegistry.findVMById(id)
        if (vm == null) {
          renderError(ctx, 404, "There is no VM with ID `$id'")
        } else {
          val json = JsonUtils.toJson(vm)
          ctx.response()
              .putHeader("content-type", "application/json")
              .end(json.encode())
        }
      }
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

    if (status == SubmissionRegistry.ProcessChainStatus.RUNNING) {
      val response = try {
        vertx.eventBus().requestAwait<Double?>(LOCAL_AGENT_ADDRESS_PREFIX + id, json {
          obj(
              "action" to "getProgress"
          )
        })
      } catch (_: ReplyException) {
        null
      }
      if (response?.body() != null) {
        processChain.put("estimatedProgress", response.body())
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
    if (prefersHtml(ctx)) {
      renderAsset("ui/processchains/index.html", ctx.response())
    } else {
      launch {
        val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
        val size = ctx.request().getParam("size")?.toIntOrNull() ?: 10

        val submissionId: String? = ctx.request().getParam("submissionId")

        val status = ctx.request().getParam("status")?.let {
          try {
            SubmissionRegistry.ProcessChainStatus.valueOf(it)
          } catch (e: IllegalArgumentException) {
            renderError(ctx, 400, "Invalid status: $it")
            return@launch
          }
        }

        val list = submissionRegistry.findProcessChains(submissionId = submissionId,
            status = status, size = size, offset = offset, order = -1).map { p ->
          JsonUtils.toJson(p.first).also {
            it.remove("executables")
            amendProcessChain(it, p.second)
          }
        }

        val total = submissionRegistry.countProcessChains(submissionId, status)
        val encodedJson = JsonArray(list).encode()

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
    if (prefersHtml(ctx)) {
      renderAsset("ui/processchains/[id].html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val processChain = submissionRegistry.findProcessChainById(id)
        if (processChain == null) {
          renderError(ctx, 404, "There is no process chain with ID `$id'")
        } else {
          val json = JsonUtils.toJson(processChain)
          val submissionId = submissionRegistry.getProcessChainSubmissionId(id)
          amendProcessChain(json, submissionId, true)
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
      renderError(ctx, 400, "Invalid request body: " + e.message)
      return
    }

    if (update == null) {
      renderError(ctx, 400, "Missing request body")
      return
    }

    val strStatus = try {
      update.getString("status")
    } catch (e: ClassCastException) {
      renderError(ctx, 400, "`status' property must be a string")
      return
    }

    if (strStatus == null) {
      renderError(ctx, 400, "Missing `status' property")
      return
    }

    val status = try {
      SubmissionRegistry.ProcessChainStatus.valueOf(strStatus)
    } catch (e: IllegalArgumentException) {
      renderError(ctx, 400, "Invalid `status' property")
      return
    }

    launch {
      val processChain = submissionRegistry.findProcessChainById(id)
      if (processChain == null) {
        renderError(ctx, 404, "There is no process chain with ID `$id'")
      } else {
        if (status == SubmissionRegistry.ProcessChainStatus.CANCELLED) {
          val currentStatus = submissionRegistry.getProcessChainStatus(id)
          if (currentStatus == SubmissionRegistry.ProcessChainStatus.REGISTERED) {
            submissionRegistry.setProcessChainStatus(id, currentStatus, status)
          } else if (currentStatus == SubmissionRegistry.ProcessChainStatus.RUNNING) {
            // Ask local agent (running anywhere in the cluster) to cancel
            // the process chain. Its status should be set to CANCELLED by
            // the scheduler as soon as the local agent has aborted the
            // execution and Steep has sent this information back to
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
