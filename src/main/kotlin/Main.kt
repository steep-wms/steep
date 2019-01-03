import helper.JsonUtils
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.spi.cluster.NodeListener
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.Vertx
import io.vertx.kotlin.core.deployVerticleAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.spi.cluster.hazelcast.ConfigUtil
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import org.yaml.snakeyaml.Yaml
import java.io.File

var globalNodeId: String = "localhost"

suspend fun main(args : Array<String>) {
  val hazelcastConfig = ConfigUtil.loadConfig()
  val mgr = HazelcastClusterManager(hazelcastConfig)

  val options = VertxOptions().setClusterManager(mgr)
  val vertx = Vertx.clusteredVertxAwait(options)
  globalNodeId = mgr.nodeID

  mgr.nodeListener(object: NodeListener {
    override fun nodeAdded(nodeID: String?) {
      vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_ADDED, nodeID)
    }

    override fun nodeLeft(nodeID: String?) {
      vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_LEFT, nodeID)
    }
  })

  vertx.deployVerticleAwait(Main::class.qualifiedName!!)
}

/**
 * The JobManager's main verticle
 * @author Michel Kraemer
 */
class Main : CoroutineVerticle() {
  companion object {
    val nodeId: String get() = globalNodeId
  }

  override suspend fun start() {
    // load configuration
    val confFileStr = File("conf/jobmanager.yaml").readText()
    val yaml = Yaml()
    val m = yaml.load<Map<String, Any>>(confFileStr)
    val conf = JsonUtils.flatten(JsonObject(m))
    overwriteWithEnvironmentVariables(conf, System.getenv())

    // deploy verticles
    val options = DeploymentOptions(conf)
    vertx.deployVerticleAwait(Scheduler::class.qualifiedName!!, options)
    vertx.deployVerticleAwait(JobManager::class.qualifiedName!!, options)
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
