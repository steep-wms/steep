import cloud.CloudManager
import com.hazelcast.core.MembershipAdapter
import com.hazelcast.core.MembershipEvent
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.shell.ShellService
import io.vertx.ext.shell.command.Command
import io.vertx.ext.shell.command.CommandRegistry
import io.vertx.kotlin.core.DeploymentOptions
import io.vertx.kotlin.core.Vertx
import io.vertx.kotlin.core.deployVerticleAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.ext.shell.ShellServiceOptions
import io.vertx.kotlin.ext.shell.term.TelnetTermOptions
import io.vertx.spi.cluster.hazelcast.ConfigUtil
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import org.yaml.snakeyaml.Yaml
import shell.AsyncMapEntries
import shell.AsyncMapGet
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Enumeration
import java.util.concurrent.CountDownLatch

const val ATTR_AGENT_ID = "Agent-ID"
var globalAgentId: String = "localhost"

suspend fun main(args : Array<String>) {
  // load configuration
  val confFileStr = File("conf/jobmanager.yaml").readText()
  val yaml = Yaml()
  val m = yaml.load<Map<String, Any>>(confFileStr)
  val conf = JsonUtils.flatten(JsonObject(m))
  overwriteWithEnvironmentVariables(conf, System.getenv())

  // load override config file
  val overrideConfigFile = conf.getString(ConfigConstants.OVERRIDE_CONFIG_FILE)
  if (overrideConfigFile != null) {
    val overrideConfFileStr = File(overrideConfigFile).readText()
    val overrideMap = yaml.load<Map<String, Any>>(overrideConfFileStr)
    val overrideConf = JsonUtils.flatten(JsonObject(overrideMap))
    overwriteWithEnvironmentVariables(overrideConf, System.getenv())
    overrideConf.forEach { (k, v) -> conf.put(k, v) }
  }

  // load hazelcast config
  val hazelcastConfig = ConfigUtil.loadConfig()

  // set hazelcast public address
  val publicAddress = conf.getString(ConfigConstants.CLUSTER_HAZELCAST_PUBLIC_ADDRESS)
  if (publicAddress != null) {
    hazelcastConfig.networkConfig.publicAddress = publicAddress
  }

  // set hazelcast port
  val port = conf.getInteger(ConfigConstants.CLUSTER_HAZELCAST_PORT)
  if (port != null) {
    hazelcastConfig.networkConfig.port = port
    hazelcastConfig.networkConfig.portCount = 1
  }

  // set hazelcast interfaces
  val interfaces = conf.getJsonArray(ConfigConstants.CLUSTER_HAZELCAST_INTERFACES)
  if (interfaces != null) {
    hazelcastConfig.networkConfig.interfaces.isEnabled = true
    hazelcastConfig.networkConfig.interfaces.interfaces = interfaces.map { it.toString() }
  }

  // replace hazelcast members
  val members = conf.getJsonArray(ConfigConstants.CLUSTER_HAZELCAST_MEMBERS)
  if (members != null) {
    hazelcastConfig.networkConfig.join.tcpIpConfig.members = members.map { it.toString() }
  }

  // enable TCP or multicast
  val tcpEnabled = conf.getBoolean(ConfigConstants.CLUSTER_HAZELCAST_TCPENABLED, false)
  hazelcastConfig.networkConfig.join.multicastConfig.isEnabled = !tcpEnabled
  hazelcastConfig.networkConfig.join.tcpIpConfig.isEnabled = tcpEnabled

  globalAgentId = conf.getString(ConfigConstants.AGENT_ID, UniqueID.next())
  hazelcastConfig.memberAttributeConfig.setStringAttribute(ATTR_AGENT_ID, globalAgentId)

  // configure event bus
  val mgr = HazelcastClusterManager(hazelcastConfig)
  val options = VertxOptions().setClusterManager(mgr)
  val eventBusHost = conf.getString(ConfigConstants.CLUSTER_EVENTBUS_HOST) ?: getDefaultAddress()
  eventBusHost?.let { options.setClusterHost(it) }
  val eventBusPort = conf.getInteger(ConfigConstants.CLUSTER_EVENTBUS_PORT)
  eventBusPort?.let { options.setClusterPort(it) }
  val eventPublicHost = conf.getString(ConfigConstants.CLUSTER_EVENTBUS_PUBLIC_HOST)
  eventPublicHost?.let { options.setClusterPublicHost(it) }
  val eventBusPublicPort = conf.getInteger(ConfigConstants.CLUSTER_EVENTBUS_PUBLIC_PORT)
  eventBusPublicPort?.let { options.setClusterPublicPort(it) }

  // start Vert.x
  val vertx = Vertx.clusteredVertxAwait(options)

  // listen to added and left cluster nodes
  // BUGFIX: do not use mgr.nodeListener() or you will override Vert.x's
  // internal HAManager!
  mgr.hazelcastInstance.cluster.addMembershipListener(object: MembershipAdapter() {
    override fun memberAdded(membershipEvent: MembershipEvent) {
      if (mgr.isActive) {
        val agentId = membershipEvent.member.getStringAttribute(ATTR_AGENT_ID)
        vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_ADDED, agentId)
      }
    }

    override fun memberRemoved(membershipEvent: MembershipEvent) {
      if (mgr.isActive) {
        val agentId = membershipEvent.member.getStringAttribute(ATTR_AGENT_ID)
        vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_LEFT, agentId)
      }
    }
  })

  // start JobManager's main verticle
  val deploymentOptions = DeploymentOptions(conf)
  try {
    vertx.deployVerticleAwait(Main::class.qualifiedName!!, deploymentOptions)
  } catch (e: Exception) {
    e.printStackTrace()
    System.exit(1)
    return
  }

  // enable graceful shutdown
  Runtime.getRuntime().addShutdownHook(Thread {
    val l = CountDownLatch(1)
    vertx.close {
      l.countDown()
    }
    l.await()
  })
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
    val agentId: String get() = globalAgentId
  }

  /**
   * Start the Vert.x Shell service
   */
  private fun createShell() {
    val options = ShellServiceOptions(telnetOptions = TelnetTermOptions(
        host = "localhost",
        port = 5000
    ))
    ShellService.create(vertx, options).start()

    val registry = CommandRegistry.getShared(vertx)
    registry.registerCommand(Command.create(vertx, AsyncMapGet::class.java))
    registry.registerCommand(Command.create(vertx, AsyncMapEntries::class.java))
  }

  override suspend fun start() {
    createShell()

    val options = DeploymentOptions(config)
    if (config.getBoolean(ConfigConstants.CLOUD_ENABLED, false)) {
      vertx.deployVerticleAwait(CloudManager::class.qualifiedName!!, options)
    }
    vertx.deployVerticleAwait(Scheduler::class.qualifiedName!!, options)
    vertx.deployVerticleAwait(Controller::class.qualifiedName!!, options)
    if (config.getBoolean(ConfigConstants.AGENT_ENABLED, true)) {
      vertx.deployVerticleAwait(JobManager::class.qualifiedName!!, options)
    }
    if (config.getBoolean(ConfigConstants.HTTP_ENABLED, true)) {
      vertx.deployVerticleAwait(HttpEndpoint::class.qualifiedName!!, options)
    }
  }
}
