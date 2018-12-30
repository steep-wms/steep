import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.slf4j.LoggerFactory

/**
 * This verticle runs an HTTP server that accepts process chains and executes
 * them through a [LocalAgent]
 * @author Michel Kraemer
 */
class AgentVerticle : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(AgentVerticle::class.java)
  }

  override suspend fun start() {
    val host = config.getString(ConfigConstants.AGENT_HOST, "localhost")
    val port = config.getInteger(ConfigConstants.AGENT_PORT, 8007)

    val options = HttpServerOptions()
        .setCompressionSupported(true)
    val server = vertx.createHttpServer(options)
    val router = Router.router(vertx)



    server.requestHandler(router).listenAwait(port, host)

    log.info("JobManager agent deployed to http://$host:$port ...")
  }
}
