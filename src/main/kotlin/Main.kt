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
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Enumeration

var globalNodeId: String = "localhost"

suspend fun main(args : Array<String>) {
  // load configuration
  val confFileStr = File("conf/jobmanager.yaml").readText()
  val yaml = Yaml()
  val m = yaml.load<Map<String, Any>>(confFileStr)
  val conf = JsonUtils.flatten(JsonObject(m))
  overwriteWithEnvironmentVariables(conf, System.getenv())

  // load hazelcast config
  val hazelcastConfig = ConfigUtil.loadConfig()

  // set hazelcast public address
  val publicAddress = conf.getString(ConfigConstants.CLUSTER_ANNOUNCE_ADDRESS)
  if (publicAddress != null) {
    hazelcastConfig.networkConfig.publicAddress = publicAddress
  }

  // set hazelcast interfaces
  val interfaces = conf.getJsonArray(ConfigConstants.CLUSTER_INTERFACES)
  if (interfaces != null) {
    hazelcastConfig.networkConfig.interfaces.isEnabled = true
    hazelcastConfig.networkConfig.interfaces.interfaces = interfaces.map { it.toString() }
  }

  // replace hazelcast members
  val members = conf.getJsonArray(ConfigConstants.CLUSTER_MEMBERS)
  if (members != null) {
    hazelcastConfig.networkConfig.join.tcpIpConfig.isEnabled = true
    hazelcastConfig.networkConfig.join.tcpIpConfig.members = members.map { it.toString() }
  }

  // configure cluster
  val mgr = HazelcastClusterManager(hazelcastConfig)
  val options = VertxOptions().setClusterManager(mgr)
  val clusterHost = conf.getString(ConfigConstants.CLUSTER_HOST) ?: getDefaultAddress()
  clusterHost?.let { options.setClusterHost(it) }
  val clusterPort = conf.getInteger(ConfigConstants.CLUSTER_PORT)
  clusterPort?.let { options.setClusterPort(it) }
  publicAddress?.let { options.setClusterPublicHost(it) }

  // start Vert.x
  val vertx = Vertx.clusteredVertxAwait(options)

  globalNodeId = mgr.nodeID

  // listen to added and left cluster nodes
  mgr.nodeListener(object: NodeListener {
    override fun nodeAdded(nodeID: String?) {
      vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_ADDED, nodeID)
    }

    override fun nodeLeft(nodeID: String?) {
      vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_LEFT, nodeID)
    }
  })

  // start JobMananger's main verticle
  val deploymentOptions = DeploymentOptions(conf)
  try {
    vertx.deployVerticleAwait(Main::class.qualifiedName!!, deploymentOptions)
  } catch (e: Exception) {
    e.printStackTrace()
    System.exit(1)
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

/**
 * This method has been copied from [io.vertx.core.impl.launcher.commands.BareCommand]
 * released under the EPL-2.0 or Apache-2.0 license. Copyright (c) 2011-2018
 * Contributors to the Eclipse Foundation.
 */
private fun getDefaultAddress(): String? {
  val nets: Enumeration<NetworkInterface>
  try {
    nets = NetworkInterface.getNetworkInterfaces()
  } catch (e: SocketException) {
    return null
  }

  var netinf: NetworkInterface
  while (nets.hasMoreElements()) {
    netinf = nets.nextElement()

    val addresses = netinf.inetAddresses

    while (addresses.hasMoreElements()) {
      val address = addresses.nextElement()
      if (!address.isAnyLocalAddress && !address.isMulticastAddress &&
          address !is Inet6Address) {
        return address.hostAddress
      }
    }
  }

  return null
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
    val options = DeploymentOptions(config)
    vertx.deployVerticleAwait(Scheduler::class.qualifiedName!!, options)
    vertx.deployVerticleAwait(Controller::class.qualifiedName!!, options)
    vertx.deployVerticleAwait(JobManager::class.qualifiedName!!, options)
  }
}
