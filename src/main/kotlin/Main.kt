import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import cloud.CloudManager
import com.hazelcast.core.MembershipAdapter
import com.hazelcast.core.MembershipEvent
import db.PluginRegistryFactory
import helper.JsonUtils
import helper.LazyJsonObjectMessageCodec
import helper.UniqueID
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.Vertx
import io.vertx.kotlin.core.deployVerticleAwait
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.spi.cluster.hazelcast.ConfigUtil
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import model.plugins.call
import org.apache.commons.text.StringEscapeUtils
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

const val ATTR_AGENT_ID = "Agent-ID"
const val ATTR_AGENT_INSTANCES = "Agent-Instances"

suspend fun main() {
  // load configuration
  val confFileStr = File("conf/steep.yaml").readText()
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

  configureLogging(conf)

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

  val agentId = conf.getString(ConfigConstants.AGENT_ID) ?: run {
    val id = UniqueID.next()
    conf.put(ConfigConstants.AGENT_ID, id)
    id
  }

  val instances = conf.getInteger(ConfigConstants.AGENT_INSTANCES, 1)
  if (instances < 1) {
    throw IllegalArgumentException("Configuration item " +
        "`${ConfigConstants.AGENT_INSTANCES}` must be greater than 0.")
  }

  hazelcastConfig.memberAttributeConfig.setStringAttribute(ATTR_AGENT_ID, agentId)
  hazelcastConfig.memberAttributeConfig.setIntAttribute(ATTR_AGENT_INSTANCES, instances)

  // configure event bus
  val mgr = HazelcastClusterManager(hazelcastConfig)
  val options = VertxOptions().setClusterManager(mgr)
  val eventBusHost = conf.getString(ConfigConstants.CLUSTER_EVENTBUS_HOST) ?: getDefaultAddress()
  eventBusHost?.let { options.eventBusOptions.setHost(it) }
  val eventBusPort = conf.getInteger(ConfigConstants.CLUSTER_EVENTBUS_PORT)
  eventBusPort?.let { options.eventBusOptions.setPort(it) }
  val eventPublicHost = conf.getString(ConfigConstants.CLUSTER_EVENTBUS_PUBLIC_HOST)
  eventPublicHost?.let { options.eventBusOptions.setClusterPublicHost(it) }
  val eventBusPublicPort = conf.getInteger(ConfigConstants.CLUSTER_EVENTBUS_PUBLIC_PORT)
  eventBusPublicPort?.let { options.eventBusOptions.setClusterPublicPort(it) }

  // start Vert.x
  val vertx = Vertx.clusteredVertxAwait(options)

  // listen to added and left cluster nodes
  // BUGFIX: do not use mgr.nodeListener() or you will override Vert.x's
  // internal HAManager!
  mgr.hazelcastInstance.cluster.addMembershipListener(object : MembershipAdapter() {
    override fun memberAdded(membershipEvent: MembershipEvent) {
      if (mgr.isActive) {
        val memberAgentId = membershipEvent.member.getStringAttribute(ATTR_AGENT_ID)
        val memberInstances = membershipEvent.member.getIntAttribute(ATTR_AGENT_INSTANCES)
        vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_ADDED, json {
          obj(
              "agentId" to memberAgentId,
              "instances" to memberInstances
          )
        })
      }
    }

    override fun memberRemoved(membershipEvent: MembershipEvent) {
      if (mgr.isActive) {
        val memberAgentId = membershipEvent.member.getStringAttribute(ATTR_AGENT_ID)
        val memberInstances = membershipEvent.member.getIntAttribute(ATTR_AGENT_INSTANCES)
        vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_LEFT, json {
          obj(
              "agentId" to memberAgentId,
              "instances" to memberInstances
          )
        })
      }
    }
  })

  // start Steep's main verticle
  val deploymentOptions = deploymentOptionsOf(conf)
  try {
    vertx.deployVerticleAwait(Main::class.qualifiedName!!, deploymentOptions)
  } catch (e: Exception) {
    e.printStackTrace()
    exitProcess(1)
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
 * [ConfigConstants.getConfigKeys] and from [conf] and save the found values
 * using the config key in the config object.
 * @param conf the config object
 * @param env the map with the environment variables
 */
private fun overwriteWithEnvironmentVariables(conf: JsonObject,
    env: Map<String, String>) {
  val names = (ConfigConstants.getConfigKeys() + conf.fieldNames()).map {
    it.toUpperCase().replace(".", "_") to it }.toMap()
  env.forEach { (k, v) ->
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
 * Amend the `logback.xml` file from the classpath with the configuration
 * properties found in the [conf] object and then configure the log framework
 */
fun configureLogging(conf: JsonObject) {
  val context = LoggerFactory.getILoggerFactory() as LoggerContext

  val level = conf.getString(ConfigConstants.LOGS_LEVEL)
  if (level != "TRACE" && level != "DEBUG" && level != "INFO" &&
      level != "WARN" && level != "ERROR" && level != "OFF") {
    throw IllegalArgumentException("Configuration item " +
        "`${ConfigConstants.LOGS_LEVEL}` must be one of `TRACE', `DEBUG', " +
        "`INFO', `WARN', `ERROR', `OFF'.")
  }

  val xml = StringBuilder("<configuration>")

  val mainEnabled = conf.getBoolean(ConfigConstants.LOGS_MAIN_ENABLED, false)
  if (mainEnabled) {
    val mainLogFile = conf.getString(ConfigConstants.LOGS_MAIN_LOGFILE, "steep.log")
    val dot = mainLogFile.lastIndexOf('.')

    val encoder = """
      <encoder class="ch.qos.logback.core.encoder.LayoutWrappingEncoder">
          <layout class="ch.qos.logback.classic.PatternLayout">
              <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
          </layout>
      </encoder>
    """

    val rolloverEnabled = conf.getBoolean(ConfigConstants.LOGS_MAIN_DAILYROLLOVER_ENABLED, true)
    if (rolloverEnabled) {
      val rolloverFilePattern = if (dot > 0) {
        mainLogFile.substring(0, dot) + ".%d{yyyy-MM-dd}" + mainLogFile.substring(dot)
      } else {
        "$mainLogFile.%d{yyyy-MM-dd}"
      }
      val rolloverMaxDays = conf.getInteger(ConfigConstants.LOGS_MAIN_DAILYROLLOVER_MAXDAYS, 7)
      val rolloverMaxSize = conf.getLong(ConfigConstants.LOGS_MAIN_DAILYROLLOVER_MAXSIZE, 104857600)

      xml.append("""
        <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            $encoder
            <file>${StringEscapeUtils.escapeXml11(mainLogFile)}</file>
            <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                <!-- daily rollover -->
                <fileNamePattern>${StringEscapeUtils.escapeXml11(rolloverFilePattern)}</fileNamePattern>

                <!-- keep n days' worth of history capped at a total size -->
                <maxHistory>$rolloverMaxDays</maxHistory>
                <totalSizeCap>$rolloverMaxSize</totalSizeCap>
            </rollingPolicy>
        </appender>
      """)
    } else {
      xml.append("""
        <appender name="FILE" class="ch.qos.logback.core.FileAppender">
            $encoder
            <file>${StringEscapeUtils.escapeXml11(mainLogFile)}</file>
        </appender>
      """)
    }
  }

  xml.append("""<root level="${StringEscapeUtils.escapeXml11(level)}">""")
  if (mainEnabled) {
    xml.append("""<appender-ref ref="FILE" />""")
  }
  xml.append("""
      </root>
    </configuration>
  """)

  val configurator = JoranConfigurator()
  configurator.context = context
  configurator.doConfigure(xml.toString().byteInputStream())
}

/**
 * Steep's main verticle
 * @author Michel Kraemer
 */
class Main : CoroutineVerticle() {
  override suspend fun start() {
    vertx.eventBus().registerCodec(LazyJsonObjectMessageCodec())

    PluginRegistryFactory.initialize(vertx)

    val pluginRegistry = PluginRegistryFactory.create()
    for (initializer in pluginRegistry.getInitializers()) {
      initializer.call(vertx)
    }

    val options = deploymentOptionsOf(config)

    if (config.getBoolean(ConfigConstants.CLOUD_ENABLED, false)) {
      vertx.deployVerticleAwait(CloudManager::class.qualifiedName!!, options)
    }

    if (config.getBoolean(ConfigConstants.SCHEDULER_ENABLED, true)) {
      vertx.deployVerticleAwait(Scheduler::class.qualifiedName!!, options)
    }

    if (config.getBoolean(ConfigConstants.CONTROLLER_ENABLED, true)) {
      vertx.deployVerticleAwait(Controller::class.qualifiedName!!, options)
    }

    if (config.getBoolean(ConfigConstants.AGENT_ENABLED, true)) {
      val agentId = config.getString(ConfigConstants.AGENT_ID) ?:
          throw RuntimeException("Missing agentId")
      val instances = config.getInteger(ConfigConstants.AGENT_INSTANCES, 1)
      for (i in 1..instances) {
        val id = if (i == 1) agentId else "$agentId[$i]"
        val configWithAgentId = config.copy().put(ConfigConstants.AGENT_ID, id)
        val optionsWithAgentId = deploymentOptionsOf(configWithAgentId)
        vertx.deployVerticleAwait(Steep::class.qualifiedName!!, optionsWithAgentId)
      }
    }

    if (config.getBoolean(ConfigConstants.HTTP_ENABLED, true)) {
      vertx.deployVerticleAwait(HttpEndpoint::class.qualifiedName!!, options)
    }
  }
}
