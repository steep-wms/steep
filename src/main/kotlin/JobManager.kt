import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.deployVerticleAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle

/**
 * The application's main verticle
 * @author Michel Kraemer
 */
class JobManager : CoroutineVerticle() {
  override suspend fun start() {
    val options = DeploymentOptions(config)
    vertx.deployVerticleAwait(ProcessChainManager::class.qualifiedName!!, options)
  }
}
