import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.CONTROLLER_LOOKUP_NOW
import AddressConstants.LOCAL_AGENT_ADDRESS_PREFIX
import AddressConstants.LOGS_PROCESSCHAINS_PREFIX
import AddressConstants.PROCESSCHAINS_ADDED
import AddressConstants.PROCESSCHAINS_ADDED_SIZE
import AddressConstants.PROCESSCHAIN_ALL_PRIORITY_CHANGED
import AddressConstants.PROCESSCHAIN_ALL_STATUS_CHANGED
import AddressConstants.PROCESSCHAIN_ENDTIME_CHANGED
import AddressConstants.PROCESSCHAIN_ERRORMESSAGE_CHANGED
import AddressConstants.PROCESSCHAIN_PRIORITY_CHANGED
import AddressConstants.PROCESSCHAIN_PROGRESS_CHANGED
import AddressConstants.PROCESSCHAIN_STARTTIME_CHANGED
import AddressConstants.PROCESSCHAIN_STATUS_CHANGED
import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_BUSY
import AddressConstants.REMOTE_AGENT_IDLE
import AddressConstants.REMOTE_AGENT_LEFT
import AddressConstants.REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
import AddressConstants.SUBMISSIONS_DELETED
import AddressConstants.SUBMISSION_ADDED
import AddressConstants.SUBMISSION_ENDTIME_CHANGED
import AddressConstants.SUBMISSION_ERRORMESSAGE_CHANGED
import AddressConstants.SUBMISSION_PRIORITY_CHANGED
import AddressConstants.SUBMISSION_STARTTIME_CHANGED
import AddressConstants.SUBMISSION_STATUS_CHANGED
import AddressConstants.VMS_DELETED
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
import com.github.zafarkhaja.semver.expr.CompositeExpression.Helper.gte
import com.github.zafarkhaja.semver.expr.CompositeExpression.Helper.lte
import db.MetadataRegistry
import db.MetadataRegistryFactory
import db.PluginRegistry
import db.PluginRegistryFactory
import db.SubmissionRegistry
import db.SubmissionRegistryFactory
import db.VMRegistry
import db.VMRegistryFactory
import db.migration.removeExecuteActionParameters
import helper.JsonUtils
import helper.RangeParser
import helper.UniqueID
import helper.WorkflowValidator
import helper.YamlUtils
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.eventbus.ReplyException
import io.vertx.core.eventbus.ReplyFailure
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServer
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
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.toReceiveChannel
import io.vertx.micrometer.PrometheusScrapingHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.Submission
import model.Version
import model.cloud.VM
import model.workflow.Workflow
import org.apache.commons.text.WordUtils
import org.parboiled.errors.ParserRuntimeException
import org.slf4j.LoggerFactory
import search.QueryCompiler
import search.SearchResultMatcher
import search.Type
import java.time.DateTimeException
import java.time.ZoneId
import java.time.zone.ZoneRulesException
import java.util.regex.Pattern
import kotlin.math.max
import com.github.zafarkhaja.semver.Version as SemVersion

/**
 * Steep's main API entry point
 * @author Michel Kraemer
 */
class HttpEndpoint : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(HttpEndpoint::class.java)

    private val VERSION: Version = JsonUtils.readValue(
        HttpEndpoint::class.java.getResource("/version.json")!!)

    private val JSON = ParsableMIMEValue("application/json")
    private val HTML = ParsableMIMEValue("text/html")
  }

  private lateinit var metadataRegistry: MetadataRegistry
  private lateinit var pluginRegistry: PluginRegistry
  private lateinit var agentRegistry: AgentRegistry
  private lateinit var submissionRegistry: SubmissionRegistry
  private lateinit var vmRegistry: VMRegistry
  private lateinit var basePath: String
  private lateinit var server: HttpServer

  override suspend fun start() {
    metadataRegistry = MetadataRegistryFactory.create(vertx)
    pluginRegistry = PluginRegistryFactory.create()
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
    server = vertx.createHttpServer(options)
    val router = Router.router(vertx)

    val bodyHandler = BodyHandler.create()
        .setHandleFileUploads(false)
        .setBodyLimit(config.getLong(ConfigConstants.HTTP_POST_MAX_SIZE, 1024L * 1024L))

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

    router.get("/health")
      .produces("application/json")
      .handler(this::onGetHealth)

    router.head("/logs/processchains/:id/?")
        .handler { ctx -> onGetProcessChainLogById(ctx, true) }

    router.get("/logs/processchains/:id/?")
        .produces("text/plain")
        .produces("text/html")
        .handler(this::onGetProcessChainLogById)

    router.get("/new")
        .produces("text/html")
        .handler(this::onNew)

    router.get("/new/workflow")
        .produces("text/html")
        .handler(this::onNewWorkflow)

    router.get("/search")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetSearch)

    router.get("/services")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetServices)

    router.get("/services/:id/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetServiceById)

    router.get("/plugins")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetPlugins)

    router.get("/plugins/:name/?")
        .produces("application/json")
        .produces("text/html")
        .handler(this::onGetPluginByName)

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
        .handler(PrometheusScrapingHandler.create())

    // a static handler that replaces placeholders in assets
    val placeholderHandler = { replaceFavicons: Boolean -> { context: RoutingContext ->
      val request = context.request()
      if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
        context.next()
      } else {
        val path = HttpUtils.removeDots(URIDecoder.decodeURIComponent(context.normalizedPath(), false))?.let {
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
    val sockJSRouter = sockJSHandler.bridge(SockJSBridgeOptions()
        .addOutboundPermitted(PermittedOptions()
            .setAddressRegex(Pattern.quote(LOGS_PROCESSCHAINS_PREFIX) + ".+"))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_ADDED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_STARTTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_ENDTIME_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_STATUS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_PRIORITY_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSION_ERRORMESSAGE_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(SUBMISSIONS_DELETED))
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
            .setAddress(PROCESSCHAIN_PRIORITY_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_ALL_PRIORITY_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_ERRORMESSAGE_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(PROCESSCHAIN_PROGRESS_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(CLUSTER_NODE_LEFT))
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
            .setAddress(VM_REASON_CHANGED))
        .addOutboundPermitted(PermittedOptions()
            .setAddress(VMS_DELETED)))
    router.route("/eventbus/*")
        .handler(BodyHandler.create()
            .setBodyLimit(config.getLong(ConfigConstants.HTTP_POST_MAX_SIZE, 1024L * 1024L)))
        .subRouter(sockJSRouter)

    val baseRouter = Router.router(vertx)
    baseRouter.route("$basePath/*").subRouter(router)
    server.requestHandler(baseRouter).listen(port, host).await()

    log.info("HTTP endpoint deployed to http://$host:$port$basePath")
  }

  override suspend fun stop() {
    log.info("Stopping HTTP endpoint ...")
    server.close().await()
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
    val maxAgeSeconds = config.getInteger(ConfigConstants.HTTP_CORS_MAX_AGE_SECONDS, -1)
    corsHandler.maxAgeSeconds(maxAgeSeconds)

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

        try {
          val agents = agentIds.map { vertx.eventBus().request<JsonObject>(
              REMOTE_AGENT_ADDRESS_PREFIX + it, msg).await() }.map { it.body() }

          val result = JsonArray(agents).encode()

          ctx.response()
              .putHeader("content-type", "application/json")
              .end(result)
        } catch (t: Throwable) {
          if (t is ReplyException && t.failureType() == ReplyFailure.NO_HANDLERS) {
            ctx.response()
              .setStatusCode(503)
              .end("Could not request agent information. At least one agent is not available.")
          } else {
            log.error("Could not request agent information", t)
            ctx.response()
              .setStatusCode(500)
              .end("Could not request agent information")
          }
        }
      }
    }
  }

  /**
   * Get a single agent by ID
   * @param ctx the routing context
   */
  private fun onGetAgentById(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/agents/[id].html/index.html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val msg = json {
          obj(
              "action" to "info"
          )
        }

        try {
          val agent = vertx.eventBus().request<JsonObject>(
              REMOTE_AGENT_ADDRESS_PREFIX + id, msg).await().body()
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
   * Get contents of a process chain log file by process chain ID
   */
  private fun onGetProcessChainLogById(ctx: RoutingContext, headersOnly: Boolean = false) {
    val forceDownload = ctx.request().getParam("forceDownload").toBoolean()
    if (!forceDownload && prefersHtml(ctx)) {
      renderAsset("ui/logs/processchains/[id].html/index.html", ctx.response())
      return
    }

    // the 'x-range' header works just like the 'range' header but allows
    // client applications to send requests with "Accept-Encoding: gzip",
    // which does not work in most browsers when sending range requests
    // because they force "Accept-Encoding: identity" in this case
    val extendedRange = ctx.request().getHeader("x-range")
    val usesExtendedRange = extendedRange != null

    val (rangeStart, rangeEnd) = run {
      val range = extendedRange ?: ctx.request().getHeader("Range")
      if (range == null) {
        (null to null)
      } else {
        val parsedRange = RangeParser.parse(range)
        if (parsedRange == null) {
          renderError(ctx, 416)
          return
        } else {
          (parsedRange.first to parsedRange.second)
        }
      }
    }

    launch {
      val id = ctx.pathParam("id")

      // check if there is a process chain with this ID
      try {
        submissionRegistry.getProcessChainStatus(id)
      } catch (e: NoSuchElementException) {
        renderError(ctx, 404, if (headersOnly) null else "There is no process " +
            "chain with ID `$id'")
        return@launch
      }

      var receivedAny = false
      var foundSize: Long? = null
      var clientException = false
      val agentIds = agentRegistry.getPrimaryAgentIds()
      val resp = ctx.response()
      try {
        for (agentId in agentIds) {
          val address = REMOTE_AGENT_ADDRESS_PREFIX + agentId +
              REMOTE_AGENT_PROCESSCHAINLOGS_SUFFIX
          val replyAddress = "$address.reply.${UniqueID.next()}"

          // register consumer to receive responses from agent
          val consumer = vertx.eventBus().consumer<JsonObject>(replyAddress)
          val consumerRegisteredPromise = Promise.promise<Unit>()
          // completionHandler must be set before calling consumer.toChannel()
          consumer.completionHandler { ar ->
            if (ar.succeeded()) {
              consumerRegisteredPromise.complete()
            } else {
              consumerRegisteredPromise.fail(ar.cause())
            }
          }
          val channel = consumer.toReceiveChannel(vertx)

          // Important! Wait for consumer registration to be propagated across
          // cluster before sending any request. Otherwise, responses might
          // be sent too early!
          consumerRegisteredPromise.future().await()

          // create periodic timer that cancels the channel on timeout
          val timeoutTimerId = vertx.setPeriodic(1000L * 30) { timerId ->
            if (receivedAny) {
              receivedAny = false
              return@setPeriodic
            }
            channel.cancel(CancellationException("Timeout while waiting for " +
                "log data from agent"))
            vertx.cancelTimer(timerId)
          }

          try {
            val msg = json {
              obj(
                  "action" to if (headersOnly) "exists" else "fetch",
                  "id" to id,
                  "replyAddress" to replyAddress
              )
            }
            rangeStart?.let { msg.put("start", it) }
            rangeEnd?.let { msg.put("end", it) }
            vertx.eventBus().send(address, msg)

            for (reply in channel) {
              receivedAny = true

              val obj = reply.body()
              if (obj.isEmpty) {
                // end marker
                break
              }

              val error = obj.getInteger("error")
              if (error != null) {
                if (error == 404) {
                  // agent does not have the log file
                } else {
                  throw ReplyException(ReplyFailure.ERROR, error, obj.getString("message"))
                }
                break
              }

              val size = obj.getLong("size")
              val start = obj.getLong("start")
              val end = obj.getLong("end")
              val length = obj.getLong("length")
              val data = obj.getString("data")
              if (size != null) {
                // prepare response
                resp.putHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                    .putHeader(HttpHeaderNames.ACCEPT_RANGES, "bytes")
                    .putHeader(HttpHeaderNames.CONTENT_LENGTH, length.toString())
                if (rangeStart != null || rangeEnd != null) {
                  if (usesExtendedRange) {
                    // use status code 200 so we can send gzip'd response
                    resp.putHeader("x-content-range", "bytes $start-$end/$size")
                    resp.statusCode = 200
                  } else {
                    resp.putHeader(HttpHeaderNames.CONTENT_RANGE, "bytes $start-$end/$size")
                    resp.statusCode = 206
                  }
                }
                if (forceDownload) {
                  resp.putHeader(HttpHeaderNames.CONTENT_DISPOSITION,
                      "attachment; filename=\"${id}.log\"")
                }
                foundSize = size
                reply.reply(null) // acknowledge reception of this message
                if (headersOnly) {
                  break
                }
              } else if (data != null) {
                if (resp.writeQueueFull()) {
                  val drainPromise = Promise.promise<Unit>()
                  resp.exceptionHandler { drainPromise.fail(it) }
                  resp.drainHandler { drainPromise.complete() }
                  try {
                    drainPromise.future().await()
                  } catch (t: Throwable) {
                    reply.fail(500, t.message)
                    @Suppress("UNUSED_VALUE") // will be used in catch clause below
                    clientException = true
                    throw t
                  } finally {
                    resp.drainHandler(null)
                    resp.exceptionHandler(null)
                  }
                }

                resp.write(data)

                reply.reply(null) // request more data
              }
            }
          } finally {
            consumer.unregister()
            vertx.cancelTimer(timeoutTimerId)
          }

          if (foundSize != null) {
            break
          }
        }
      } catch (t: Throwable) {
        if (!clientException) {
          if (t is ReplyException) {
            renderError(ctx, t.failureCode(), t.message)
          } else {
            renderError(ctx, 500, t.message)
          }
        } else {
          log.error("Could not send log file to client", t)
        }
        return@launch
      }

      if (!headersOnly) {
        if (foundSize == null) {
          // no agent has the log file!
          renderError(ctx, 404, "Log file of process chain `$id' could not " +
              "be found. Possible reasons: (1) the process chain has not " +
              "produced any output (yet), (2) the agent that has executed " +
              "the process chain is not available anymore, (3) process chain " +
              "logging is disabled in Steep's configuration")
        } else {
          resp.end()
        }
      } else {
        if (foundSize == null) {
          renderError(ctx, 404)
        } else {
          resp.putHeader(HttpHeaderNames.CONTENT_LENGTH, foundSize.toString())
              .putHeader(HttpHeaderNames.ACCEPT_RANGES, "bytes")
              .end()
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
   * Check if Steep is healthy.
   * @param ctx the routing context
   */
  private fun onGetHealth(ctx: RoutingContext) = launch {
    var allComponentsHealthy = true

    suspend fun <T> checkComponent(block: suspend () -> T) = try {
      jsonObjectOf("health" to true, "count" to block())
    } catch (t: Throwable) {
      allComponentsHealthy = false
      jsonObjectOf("health" to false, "count" to -1, "error" to t.message)
    }

    val result = json {
      obj(
        "services" to checkComponent { metadataRegistry.findServices().size },
        "agents" to checkComponent { agentRegistry.getAgentIds().size },
        "submissions" to checkComponent { submissionRegistry.countSubmissions() },
        "vms" to checkComponent { vmRegistry.countVMs() }
      )
    }
    val statusCode = if (allComponentsHealthy) {
      HttpResponseStatus.OK
    } else {
      HttpResponseStatus.SERVICE_UNAVAILABLE
    }
    ctx.response()
      .putHeader("content-type", "application/json")
      .setStatusCode(statusCode.code())
      .end(result.put("health", allComponentsHealthy).encode())
  }

  /**
   * Search registry based on a user-defined query
   * @param ctx the routing context
   */
  private fun onGetSearch(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/search/index.html", ctx.response())
    } else {
      val q = ctx.request().getParam("q") ?: ""
      val offset = max(0, ctx.request().getParam("offset")?.toIntOrNull() ?: 0)
      val size = ctx.request().getParam("size")?.toIntOrNull() ?: 10

      val count = ctx.request().getParam("count", "estimate").lowercase()
      if (count != "none" && count != "estimate" && count != "exact") {
        renderError(ctx, 400, "Parameter `count' must be one of `none', " +
            "`estimate', or `exact'")
        return
      }

      val timeZone = try {
        ctx.request().getParam("timeZone")?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
      } catch (e: ZoneRulesException) {
        renderError(ctx, 400, "Unknown timezone")
        return
      } catch (e: DateTimeException) {
        renderError(ctx, 400, "Invalid timezone")
        return
      }

      launch {
        val query = try {
          QueryCompiler.compile(q, timeZone)
        } catch (e: ParserRuntimeException) {
          renderError(ctx, 400, e.message)
          return@launch
        }

        val countJobs = if (count != "none") {
          Type.values().map { type ->
            async {
              type to submissionRegistry.searchCount(query, type, count == "estimate")
            }
          }
        } else {
          emptyList()
        }

        val searchResults = submissionRegistry.search(query, size = size, offset = offset)
        val counts = countJobs.awaitAll()

        var countedWorkflows = 0L
        var countedProcessChains = 0L
        val results = JsonArray()
        for (sr in searchResults) {
          val matches = SearchResultMatcher.toMatch(sr, query)
          val ro = jsonObjectOf(
              "id" to sr.id,
              "type" to sr.type,
              "requiredCapabilities" to sr.requiredCapabilities,
              "status" to sr.status
          )
          if (sr.name != null) {
            ro.put("name", sr.name)
          }
          if (sr.startTime != null) {
            ro.put("startTime", sr.startTime)
          }
          if (sr.endTime != null) {
            ro.put("endTime", sr.endTime)
          }
          ro.put("matches", matches)
          results.add(ro)

          when (sr.type) {
            Type.WORKFLOW -> countedWorkflows++
            Type.PROCESS_CHAIN -> countedProcessChains++
          }
        }

        var total = 0L
        val result = JsonObject()
        if (count != "none") {
          val countsObj = JsonObject()
          for (c in counts) {
            val v = when (c.first) {
              Type.WORKFLOW -> if (countedWorkflows + countedProcessChains < size ||
                  countedWorkflows > c.second) countedWorkflows else c.second
              Type.PROCESS_CHAIN -> if (countedWorkflows + countedProcessChains < size ||
                  countedProcessChains > c.second) countedProcessChains else c.second
            }
            countsObj.put(c.first.type, v)
            total += v
          }
          countsObj.put("total", total)
          result.put("counts", countsObj)
        }
        result.put("results", results)

        ctx.response()
            .putHeader("content-type", "application/json")
            .putHeader("x-page-size", size.toString())
            .putHeader("x-page-offset", offset.toString())

        if (count != "none") {
          ctx.response().putHeader("x-page-total", total.toString())
        }

        ctx.response().end(JsonUtils.mapper.writeValueAsString(result))
      }
    }
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
      renderAsset("ui/services/[id].html/index.html", ctx.response())
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
   * Get a list of all plugins
   * @param ctx the routing context
   */
  private fun onGetPlugins(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/plugins/index.html", ctx.response())
    } else {
      launch {
        val plugins = pluginRegistry.getAllPlugins()
        val result = JsonArray(plugins.map { JsonUtils.toJson(it) }).encode()
        ctx.response()
          .putHeader("content-type", "application/json")
          .end(result)
      }
    }
  }

  /**
   * Get a single plugin by its name
   * @param ctx the routing context
   */
  private fun onGetPluginByName(ctx: RoutingContext) {
    if (prefersHtml(ctx)) {
      renderAsset("ui/plugins/[name].html/index.html", ctx.response())
    } else {
      launch {
        val name = ctx.pathParam("name")
        val plugin = pluginRegistry.getAllPlugins().firstOrNull { it.name == name }

        if (plugin == null) {
          renderError(ctx, 404, "There is no plugin with name `$name'")
        } else {
          val pluginObj = JsonUtils.toJson(plugin)
          ctx.response()
            .putHeader("content-type", "application/json")
            .end(pluginObj.encode())
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
    val statuses = submissionRegistry.countProcessChainsPerStatus(submissionId)
    submission.put("runningProcessChains",
        statuses[SubmissionRegistry.ProcessChainStatus.RUNNING] ?: 0L)
    submission.put("cancelledProcessChains",
        statuses[SubmissionRegistry.ProcessChainStatus.CANCELLED] ?: 0L)
    submission.put("succeededProcessChains",
        statuses[SubmissionRegistry.ProcessChainStatus.SUCCESS] ?: 0L)
    submission.put("failedProcessChains",
        statuses[SubmissionRegistry.ProcessChainStatus.ERROR] ?: 0L)
    submission.put("totalProcessChains", statuses.values.sum())

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
        val submissions = submissionRegistry.findSubmissionsRaw(status, size,
            offset, -1, excludeWorkflows = true, excludeSources = true)
        submissions.forEach { amendSubmission(it) }

        val encodedJson = JsonUtils.mapper.writeValueAsString(submissions)
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
      renderAsset("ui/workflows/[id].html/index.html", ctx.response())
    } else {
      launch {
        val id = ctx.pathParam("id")
        val submission = submissionRegistry.findSubmissionById(id)
        if (submission == null) {
          renderError(ctx, 404, "There is no workflow with ID `$id'")
        } else {
          val json = JsonUtils.toJson(submission)
          amendSubmission(json, true)
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
      ctx.body().asJsonObject()
    } catch (e: Exception) {
      renderError(ctx, 400, "Invalid request body: " + e.message)
      return
    }

    if (update == null) {
      renderError(ctx, 400, "Missing request body")
      return
    }

    val strStatus: String? = try {
      update.getString("status")
    } catch (e: ClassCastException) {
      renderError(ctx, 400, "`status' property must be a string")
      return
    }

    val priority: Int? = try {
      update.getInteger("priority")
    } catch (e: ClassCastException) {
      renderError(ctx, 400, "`priority' property must be an integer")
      return
    }

    if (strStatus == null && priority == null) {
      renderError(ctx, 400, "Missing properties. At least one of `status' " +
          "and `priority' required")
      return
    }

    val status = strStatus?.let {
      try {
        Submission.Status.valueOf(strStatus)
      } catch (e: IllegalArgumentException) {
        renderError(ctx, 400, "Invalid `status' property")
        return
      }
    }

    launch {
      val submission = submissionRegistry.findSubmissionById(id)
      if (submission == null) {
        renderError(ctx, 404, "There is no workflow with ID `$id'")
      } else if (priority != null && submission.status != Submission.Status.ACCEPTED &&
          submission.status != Submission.Status.RUNNING) {
        // 422 Unprocessable Entity
        renderError(ctx, 422, "Cannot change priority of a finished submission")
      } else {
        if (status != submission.status && status == Submission.Status.CANCELLED) {
          // first, atomically cancel all process chains that are currently
          // registered but not running yet
          submissionRegistry.setAllProcessChainsStatus(id,
              SubmissionRegistry.ProcessChainStatus.REGISTERED,
              SubmissionRegistry.ProcessChainStatus.CANCELLED)

          // now cancel running process chains
          val pcIds = submissionRegistry.findProcessChainIdsBySubmissionIdAndStatus(
              id, SubmissionRegistry.ProcessChainStatus.RUNNING)
          // request cancellation (see also onPutProcessChainById())
          val cancelMsg = jsonObjectOf("action" to "cancel")
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

        if (priority != null) {
          // update submission priority first so all process chains generated
          // from now on get the new priority
          submissionRegistry.setSubmissionPriority(id, priority)

          // now also update priority of all process chains belonging to
          // this submission
          submissionRegistry.setAllProcessChainsPriority(id, priority)
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
    val (workflowJson, source) = try {
      val str = ctx.body().asString()
      val first = str.indexOfFirst { !it.isWhitespace() }
      val json: Map<String, Any> = if (str[first] == '{') {
        JsonUtils.readValue(str)
      } else {
        YamlUtils.readValue(str)
      }
      json to str
    } catch (e: Exception) {
      renderError(ctx, 400, "Invalid workflow JSON: " + e.message)
      return
    }

    val api = try {
      SemVersion.valueOf(workflowJson["api"].toString())
    } catch (e: Exception) {
      renderError(ctx, 400, "Invalid workflow API version: " + e.message)
      return
    }
    if (!api.satisfies(gte("4.0.0").and(lte("4.5.0")))) {
      renderError(ctx, 400, "Invalid workflow API version: $api. " +
          "Supported version range is [4.0.0, 4.5.0].")
      return
    }

    // remove deprecated action parameters
    if (removeExecuteActionParameters(workflowJson)) {
      log.warn("Found `parameters' in an execute action. Parameters " +
          "are unnecessary and were removed in workflow API " +
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
                "${WordUtils.wrap(it.details, 80, "\n  ", true)}\n\n  " +
                it.path.joinToString("->")
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
    launch {
      val reqCaps = Submission.collectRequiredCapabilities(workflow,
          metadataRegistry.findServices())
      val submission = Submission(workflow = workflow,
          requiredCapabilities = reqCaps, source = source)

      try {
        submissionRegistry.addSubmission(submission)
        ctx.response()
            .setStatusCode(202)
            .putHeader("content-type", "application/json")
            .end(JsonUtils.writeValueAsString(submission))

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
      renderAsset("ui/vms/[id].html/index.html", ctx.response())
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
        vertx.eventBus().request<Double?>(LOCAL_AGENT_ADDRESS_PREFIX + id, json {
          obj(
              "action" to "getProgress"
          )
        }).await<Message<Double?>>()
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
            status = status, size = size, offset = offset, order = -1,
            excludeExecutables = true).map { p ->
          JsonUtils.toJson(p.first).also {
            // we've already excluded the executables, but we still need to
            // make sure the result doesn't include an empty list
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
      renderAsset("ui/processchains/[id].html/index.html", ctx.response())
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
      ctx.body().asJsonObject()
    } catch (e: Exception) {
      renderError(ctx, 400, "Invalid request body: " + e.message)
      return
    }

    if (update == null) {
      renderError(ctx, 400, "Missing request body")
      return
    }

    val strStatus: String? = try {
      update.getString("status")
    } catch (e: ClassCastException) {
      renderError(ctx, 400, "`status' property must be a string")
      return
    }

    val priority: Int? = try {
      update.getInteger("priority")
    } catch (e: ClassCastException) {
      renderError(ctx, 400, "`priority' property must be an integer")
      return
    }

    if (strStatus == null && priority == null) {
      renderError(ctx, 400, "Missing properties. At least one of `status' " +
          "and `priority' required")
      return
    }

    val status = strStatus?.let {
      try {
        SubmissionRegistry.ProcessChainStatus.valueOf(strStatus)
      } catch (e: IllegalArgumentException) {
        renderError(ctx, 400, "Invalid `status' property")
        return
      }
    }

    launch {
      val processChain = submissionRegistry.findProcessChainById(id)
      if (processChain == null) {
        renderError(ctx, 404, "There is no process chain with ID `$id'")
      } else {
        val currentStatus = submissionRegistry.getProcessChainStatus(id)

        if (priority != null && currentStatus != SubmissionRegistry.ProcessChainStatus.REGISTERED &&
            currentStatus != SubmissionRegistry.ProcessChainStatus.RUNNING) {
          // 422 Unprocessable Entity
          renderError(ctx, 422, "Cannot change priority of a finished process chain")
          return@launch
        }

        if (status == SubmissionRegistry.ProcessChainStatus.CANCELLED) {
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

        if (priority != null) {
          submissionRegistry.setProcessChainPriority(id, priority)
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
