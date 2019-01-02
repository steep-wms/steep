import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.web.Router
import io.vertx.kotlin.core.http.listenAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
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
    // deploy HTTP server
    val host = config.getString(ConfigConstants.HOST, "localhost")
    val port = config.getInteger(ConfigConstants.PORT, 8080)

    val options = HttpServerOptions()
        .setCompressionSupported(true)
    val server = vertx.createHttpServer(options)
    val router = Router.router(vertx)



    server.requestHandler(router).listenAwait(port, host)

    log.info("JobManager deployed to http://$host:$port ...")
  }
}
