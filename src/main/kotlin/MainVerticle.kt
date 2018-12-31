import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.deployVerticleAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import org.yaml.snakeyaml.Yaml
import java.io.File

suspend fun main(args : Array<String>) {
  val vertx = Vertx.vertx()
  vertx.deployVerticleAwait(MainVerticle::class.qualifiedName!!)
}

/**
 * The JobManager's main verticle
 * @author Michel Kraemer
 */
class MainVerticle : CoroutineVerticle() {
  override suspend fun start() {
    // load configuration
    val confFileStr = File("conf/jobmanager.yaml").readText()
    val yaml = Yaml()
    val m = yaml.load<Map<String, Any>>(confFileStr)
    val conf = JsonUtils.flatten(JsonObject(m))
    overwriteWithEnvironmentVariables(conf, System.getenv())

    // deploy verticles
    val options = DeploymentOptions(conf)
    if (conf.getBoolean(ConfigConstants.JOBMANAGER_ENABLED, false)) {
      vertx.deployVerticleAwait(JobManagerVerticle::class.qualifiedName!!, options)
    }
    if (conf.getBoolean(ConfigConstants.AGENT_ENABLED, false)) {
      vertx.deployVerticleAwait(AgentVerticle::class.qualifiedName!!, options)
    }
  }

  /**
   * Match every environment variable against the config keys from
   * [ConfigConstants.getConfigKeys] and save the found values using
   * the config key in the config object.
   * @param conf the config object
   * @param env the map with the environment variables
   */
  private fun overwriteWithEnvironmentVariables(conf: JsonObject,
      env: Map<String, String>) {
    val names = ConfigConstants.getConfigKeys().map {
      it.toUpperCase().replace(".", "_") to it }.toMap()
    env.forEach { k, v ->
      val name = names[k.toUpperCase()]
      if (name != null) {
        val yaml = Yaml()
        val newVal = yaml.load<Any>(v)
        conf.put(name, newVal)
      }
    }
  }
}
