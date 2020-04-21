import AddressConstants.CONTROLLER_LOOKUP_NOW
import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.PROCESSCHAINS_ADDED
import AddressConstants.PROCESSCHAINS_ADDED_SIZE
import AddressConstants.PROCESSCHAIN_ALL_STATUS_CHANGED
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
import agent.AgentRegistry
import agent.AgentRegistryFactory
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
import model.workflow.Workflow
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.StringWriter
import kotlin.math.max
import com.github.zafarkhaja.semver.Version as SemVersion

/**
 * Steep's main API entry point
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
      val sha = Steep::class.java.getResourceAsStream(v)
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
  private lateinit var agentRegistry: AgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var basePath: String

  override suspend fun start() {
    metadataRegistry = MetadataRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)
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

    // a static handler that replaces placeholders in assets
    router.get("/_next/*").handler { context ->
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
          renderAsset("ui$path", context.response())
        }
      }
    }

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
            .setAddress(PROCESSCHAIN_ALL_STATUS_CHANGED))
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
   * Renders an asset to the given HTTP response. Replaces placeholders.
   */
  private fun renderAsset(name: String, response: HttpServerResponse) {
    val url = this.javaClass.getResource(name)
    if (url == null) {
      response
          .setStatusCode(404)
          .end()
      return
    }

    val html = url.readText()
        .replace("/\$\$MYBASEPATH\$\$", basePath)
        .replace("/\$\$MYBASEURL\$\$", basePath)

    val ext = name.substring(name.lastIndexOf('.') + 1)
    val contentType = MimeMapping.getMimeTypeForExtension(ext)
    response.putHeader("content-type", contentType)

    if (contentType == "text/html") {
      response
          .putHeader("cache-control", "no-cache, no-store, must-revalidate")
          .putHeader("expires", "0")
    } else {
      response
          .putHeader("cache-control", "public, max-age=" + (60 * 60 * 24 * 365)) // one year
    }

    response.end(html)
  }

  /**
   * Get information about Steep
   * @param ctx the routing context
   */
  private fun onGet(ctx: RoutingContext) {
    if (ctx.acceptableContentType == "text/html") {
      val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
      if (beta) {
        renderAsset("ui/index.html", ctx.response())
      } else {
        renderHtml("html/index.html", mapOf(
            "version" to VERSION
        ), ctx.response())
      }
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
      val agentIds = agentRegistry.getAgentIds()

      val msg = json {
        obj(
            "action" to "info"
        )
      }
      val agents = agentIds.map { vertx.eventBus().sendAwait<JsonObject>(
          REMOTE_AGENT_ADDRESS_PREFIX + it, msg) }.map { it.body() }

      val result = JsonArray(agents).encode()

      if (ctx.acceptableContentType == "text/html") {
        val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
        if (beta) {
          renderAsset("ui/agents/index.html", ctx.response())
        } else {
          renderHtml("html/agents/index.html", mapOf(
              "agents" to result
          ), ctx.response())
        }
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
          val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
          if (beta) {
            renderAsset("ui/agents/[id].html", ctx.response())
          } else {
            renderHtml("html/agents/single.html", mapOf(
                "id" to id,
                "agents" to JsonArray(agent).encode()
            ), ctx.response())
          }
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
        val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
        if (beta) {
          renderAsset("ui/services/index.html", ctx.response())
        } else {
          renderHtml("html/services/index.html", mapOf(
              "services" to result
          ), ctx.response())
        }
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
          val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
          if (beta) {
            renderAsset("ui/services/[id].html", ctx.response())
          } else {
            renderHtml("html/services/single.html", mapOf(
                "id" to id,
                "name" to service.name,
                "services" to JsonArray(serviceObj).encode()
            ), ctx.response())
          }
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
    launch {
      val isHtml = ctx.acceptableContentType == "text/html"
      val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
      val size = ctx.request().getParam("size")?.toIntOrNull() ?: 10

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
        val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
        if (beta) {
          renderAsset("ui/workflows/index.html", ctx.response())
        } else {
          renderHtml("html/workflows/index.html", mapOf(
              "workflows" to encodedJson,
              "page" to mapOf(
                  "size" to if (size < 0) total else size,
                  "offset" to offset,
                  "total" to total
              )
          ), ctx.response())
        }
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
          val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
          if (beta) {
            renderAsset("ui/workflows/[id].html", ctx.response())
          } else {
            renderHtml("html/workflows/single.html", mapOf(
                "id" to submission.id,
                "workflows" to JsonArray(json).encode()
            ), ctx.response())
          }
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
   * Recursively searches the given JSON object for store actions and removes
   * them with a warning. Support for store actions was dropped in workflow
   * API version 4.0.0.
   */
  private fun removeStoreAction(json: Map<*, *>) {
    val actions = json["actions"]
    if (actions is MutableList<*>) {
      val iterator = actions.iterator()
      while (iterator.hasNext()) {
        val a = iterator.next()
        if (a is Map<*, *>) {
          val type = a["type"]
          if ("for" == type) {
            removeStoreAction(a)
          } else if ("store" == type) {
            log.warn("Found a store action in the posted workflow. Such " +
                "actions were removed in workflow API version 4.0.0. The " +
                "action will be removed from the workflow. Use the `store' " +
                "flag on `output' parameters instead.")
            iterator.remove()
          }
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
    if (!api.satisfies(gte("3.0.0").and(lte("4.0.0")))) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow api version: $api. Supported version range is [3.0.0, 4.0.0].")
      return
    }

    // remove incompatible store action
    removeStoreAction(workflowJson)

    val workflow = try {
      JsonUtils.mapper.convertValue<Workflow>(workflowJson)
    } catch (e: Exception) {
      ctx.response()
          .setStatusCode(400)
          .end("Invalid workflow: " + e.message)
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
        ctx.response()
            .setStatusCode(500)
            .end(e.message)
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
      val size = ctx.request().getParam("size")?.toIntOrNull() ?: 10

      val submissionId: String? = ctx.request().getParam("submissionId")
      val list = if (submissionId == null) {
        submissionRegistry.findProcessChains(size, offset, -1)
      } else {
        submissionRegistry.findProcessChainsBySubmissionId(submissionId, size, offset, -1)
            .map { Pair(it, submissionId) }
      }.map { p ->
        JsonUtils.toJson(p.first).also {
          it.remove("executables")
          amendProcessChain(it, p.second)
        }
      }

      val total = if (submissionId == null) {
        submissionRegistry.countProcessChains()
      } else {
        submissionRegistry.countProcessChainsBySubmissionId(submissionId)
      }

      val encodedJson = JsonArray(list).encode()

      if (isHtml) {
        val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
        if (beta) {
          renderAsset("ui/processchains/index.html", ctx.response())
        } else {
          renderHtml("html/processchains/index.html", mapOf(
              "submissionId" to submissionId,
              "processChains" to encodedJson,
              "page" to mapOf(
                  "size" to if (size < 0) total else size,
                  "offset" to offset,
                  "total" to total
              )
          ), ctx.response())
        }
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
          val beta = ctx.request().getParam("beta")?.toBoolean() ?: false
          if (beta) {
            renderAsset("ui/processchains/[id].html", ctx.response())
          } else {
            renderHtml("html/processchains/single.html", mapOf(
                "id" to id,
                "processChains" to JsonArray(json).encode()
            ), ctx.response())
          }
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
