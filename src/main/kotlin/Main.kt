import agent.RemoteAgentRegistry
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import cloud.CloudManager
import com.hazelcast.cluster.MembershipAdapter
import com.hazelcast.cluster.MembershipEvent
import com.hazelcast.config.PartitionGroupConfig
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.LifecycleEvent
import com.hazelcast.spi.partitiongroup.PartitionGroupMetaData.PARTITION_GROUP_PLACEMENT
import db.PluginRegistryFactory
import db.VMRegistryFactory
import helper.CompressedJsonObjectMessageCodec
import helper.JsonUtils
import helper.LazyJsonObjectMessageCodec
import helper.UniqueID
import helper.loadTemplate
import helper.toDuration
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.vertx.core.Vertx.clusteredVertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.deploymentOptionsOf
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.micrometer.micrometerMetricsOptionsOf
import io.vertx.kotlin.micrometer.vertxPrometheusOptionsOf
import io.vertx.spi.cluster.hazelcast.ConfigUtil
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.plugins.call
import org.apache.commons.text.StringEscapeUtils
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Enumeration
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

const val ATTR_AGENT_ID = "Agent-ID"
const val ATTR_AGENT_INSTANCES = "Agent-Instances"

lateinit var globalVertxInstance: io.vertx.core.Vertx
lateinit var globalHazelcastInstance: HazelcastInstance

suspend fun main() {
  // load configuration
  val confFileStr = File("conf/steep.yaml").readText()
  val yaml = Yaml()
  val m = yaml.loadTemplate<Map<String, Any>>(confFileStr, mapOf("env" to System.getenv()))
  val conf = JsonUtils.flatten(JsonObject(m))
  overwriteWithEnvironmentVariables(conf, System.getenv())

  // load override config file
  val overrideConfigFile = conf.getString(ConfigConstants.OVERRIDE_CONFIG_FILE)
  if (overrideConfigFile != null) {
    val overrideConfFileStr = File(overrideConfigFile).readText()
    val overrideMap = yaml.loadTemplate<Map<String, Any>>(overrideConfFileStr,
      mapOf("env" to System.getenv()))
    val overrideConf = JsonUtils.flatten(JsonObject(overrideMap))
    overwriteWithEnvironmentVariables(overrideConf, System.getenv())
    overrideConf.forEach { (k, v) -> conf.put(k, v) }
  }

  configureLogging(conf)

  val log = LoggerFactory.getLogger(Main::class.java)

  // load hazelcast config
  val hazelcastConfig = ConfigUtil.loadConfig()

  // set hazelcast cluster name
  val clusterName = conf.getString(ConfigConstants.CLUSTER_HAZELCAST_CLUSTER_NAME)
  if (clusterName != null) {
    hazelcastConfig.clusterName = clusterName
  }

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

  // restore Hazelcast members from IP addresses of running VMs
  if (conf.getBoolean(ConfigConstants.CLUSTER_HAZELCAST_RESTORE_MEMBERS_ENABLED, false)) {
    val defaultPort = conf.getInteger(ConfigConstants.CLUSTER_HAZELCAST_RESTORE_MEMBERS_DEFAULT_PORT, 5701)
    val restoredMembers = restoreMembers(defaultPort, conf)
    log.info("Restored cluster members from VM registry: $restoredMembers")
    restoredMembers.forEach { hazelcastConfig.networkConfig.join.tcpIpConfig.addMember(it) }
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

  hazelcastConfig.memberAttributeConfig.setAttribute(ATTR_AGENT_ID, agentId)
  hazelcastConfig.memberAttributeConfig.setAttribute(ATTR_AGENT_INSTANCES, instances.toString())

  // configure placement groups
  val placementGroupName = conf.getString(ConfigConstants.CLUSTER_HAZELCAST_PLACEMENT_GROUP_NAME)
  if (placementGroupName != null) {
    hazelcastConfig.partitionGroupConfig.isEnabled = true
    hazelcastConfig.partitionGroupConfig.groupType = PartitionGroupConfig.MemberGroupType.PLACEMENT_AWARE
    hazelcastConfig.memberAttributeConfig.setAttribute(PARTITION_GROUP_PLACEMENT, placementGroupName)
  }

  // configure lite member
  val liteMember = conf.getBoolean(ConfigConstants.CLUSTER_HAZELCAST_LITE_MEMBER, false)
  if (liteMember) {
    hazelcastConfig.isLiteMember = true
  }

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

  // enable metrics
  options.metricsOptions = micrometerMetricsOptionsOf(
      enabled = true,
      micrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT,
          CollectorRegistry.defaultRegistry, Clock.SYSTEM),
      jvmMetricsEnabled = true,
      prometheusOptions = vertxPrometheusOptionsOf(enabled = true)
  )

  // start Vert.x
  val vertx = clusteredVertx(options).await()
  globalVertxInstance = vertx
  globalHazelcastInstance = mgr.hazelcastInstance

  // listen to added and left cluster nodes
  // BUGFIX: do not use mgr.nodeListener() or you will override Vert.x's
  // internal HAManager!
  mgr.hazelcastInstance.cluster.addMembershipListener(object : MembershipAdapter() {
    override fun memberAdded(membershipEvent: MembershipEvent) {
      if (mgr.isActive) {
        val memberAgentId = membershipEvent.member.getAttribute(ATTR_AGENT_ID)
        val memberInstances = membershipEvent.member.getAttribute(ATTR_AGENT_INSTANCES).toInt()
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
        val memberAgentId = membershipEvent.member.getAttribute(ATTR_AGENT_ID)
        val memberInstances = membershipEvent.member.getAttribute(ATTR_AGENT_INSTANCES).toInt()
        vertx.eventBus().publish(AddressConstants.CLUSTER_NODE_LEFT, json {
          obj(
              "agentId" to memberAgentId,
              "instances" to memberInstances
          )
        }, deliveryOptionsOf(localOnly = true))
      }
    }
  })

  mgr.hazelcastInstance.lifecycleService.addLifecycleListener { lifecycleEvent ->
    if (lifecycleEvent.state == LifecycleEvent.LifecycleState.MERGED) {
      vertx.eventBus().publish(AddressConstants.CLUSTER_LIFECYCLE_MERGED, null,
          deliveryOptionsOf(localOnly = true))
    }
  }

  // Look for orphaned entries in the remote agent registry from time to time.
  // Such entries may happen if there is a network failure during deregistration
  // of an agent.
  val lookupOrphansInterval = conf.getString(ConfigConstants.CLUSTER_LOOKUP_ORPHANS_INTERVAL, "5m")
      .toDuration().toMillis()
  val remoteAgentRegistry = RemoteAgentRegistry(vertx)
  CoroutineScope(vertx.dispatcher()).launch {
    while (true) {
      try {
        delay(lookupOrphansInterval)
        val remoteAgentIds = remoteAgentRegistry.getAgentIds()
        val memberIds = mgr.hazelcastInstance.cluster.members.map { it.getAttribute(ATTR_AGENT_ID) }.toSet()
        for (rid in remoteAgentIds) {
          val mainId = rid.indexOf('[').let { i -> if (i < 0) rid else rid.substring(0, i) }
          if (!memberIds.contains(mainId)) {
            log.warn("Found orphaned remote agent `$rid'. Forcing removal ...")
            remoteAgentRegistry.deregister(rid)
          }
        }
      } catch (t: Throwable) {
        log.warn("Could not sync remote agents with cluster members", t)
      }
    }
  }

  // start Steep's main verticle
  val deploymentOptions = deploymentOptionsOf(config = conf)
  val mainVerticleId = try {
    vertx.deployVerticle(Main::class.qualifiedName!!, deploymentOptions).await()
  } catch (e: Exception) {
    e.printStackTrace()
    exitProcess(1)
  }

  // enable graceful shutdown
  @Suppress("BlockingMethodInNonBlockingContext")
  Runtime.getRuntime().addShutdownHook(Thread {
    // gracefully undeploy all verticles
    val l1 = CountDownLatch(1)
    vertx.undeploy(mainVerticleId) {
      l1.countDown()
    }
    l1.await()

    // gracefully wait for any remaining Hazelcast message to be sent
    Thread.sleep(1000)

    // shutdown Vert.x cluster
    val l2 = CountDownLatch(1)
    vertx.close {
      l2.countDown()
    }
    l2.await()
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
  val names = (ConfigConstants.getConfigKeys() + conf.fieldNames())
      .associateBy { it.uppercase(Locale.getDefault()).replace(".", "_") }
  env.forEach { (k, v) ->
    val name = names[k.uppercase(Locale.getDefault())]
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
    val mainLogFile = conf.getString(ConfigConstants.LOGS_MAIN_LOGFILE, "logs/steep.log")
    val dot = mainLogFile.lastIndexOf('.')

    val encoder = """
      <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
        <pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{36} - %msg%n</pattern>
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

  val processChainsEnabled = conf.getBoolean(ConfigConstants.LOGS_PROCESSCHAINS_ENABLED, false)
  if (processChainsEnabled) {
    val path = conf.getString(ConfigConstants.LOGS_PROCESSCHAINS_PATH, "logs/processchains")
    val groupByPrefix = conf.getInteger(ConfigConstants.LOGS_PROCESSCHAINS_GROUPBYPREFIX, 0)
    val processChainEncoder = """
      <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
        <pattern>%d{yyyy-MM-dd HH:mm:ss} - %msg%n</pattern>
      </encoder>
    """
    xml.append("""
      <appender name="PROCESSCHAIN" class="ch.qos.logback.classic.sift.SiftingAppender">
        <discriminator class="helper.LoggerNameBasedDiscriminator" />
        <sift>
          <appender name="PROCESSCHAIN-${"$"}{loggerName}" class="helper.ProcessChainLogFileAppender">
            <loggerName>${"$"}{loggerName}</loggerName>
            <path>${path}</path>
            <groupByPrefix>${groupByPrefix}</groupByPrefix>
            $processChainEncoder
          </appender>
        </sift>
      </appender>
      <appender name="PROCESSCHAIN-EVENTBUS" class="ch.qos.logback.classic.sift.SiftingAppender">
        <discriminator class="helper.LoggerNameBasedDiscriminator" />
        <sift>
          <appender name="PROCESSCHAIN-EVENTBUS-${"$"}{loggerName}" class="helper.EventbusAppender">
            <loggerName>${"$"}{loggerName}</loggerName>
            $processChainEncoder
          </appender>
        </sift>
      </appender>
    """)
  }

  if (processChainsEnabled) {
    xml.append("""
      <logger name="agent.LocalAgent.processChain" level="INFO" additivity="false">
          <appender-ref ref="PROCESSCHAIN" />
          <appender-ref ref="PROCESSCHAIN-EVENTBUS" />
      </logger>
    """)
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

suspend fun restoreMembers(defaultPort: Int, config: JsonObject): List<String> {
  // create temporary non-clustered Vert.x instance
  val vertx = io.vertx.core.Vertx.vertx()
  try {
    // create temporary VM registry
    val vmRegistry = VMRegistryFactory.create(vertx, config)
    try {
      // find non-terminated VMs and map their IP addresses to member addresses
      val nonTerminatedVMs = vmRegistry.findNonTerminatedVMs()
      return nonTerminatedVMs.mapNotNull { vm ->
        if (vm.ipAddress != null) {
          "${vm.ipAddress}:$defaultPort"
        } else {
          null
        }
      }
    } finally {
      vmRegistry.close()
    }
  } finally {
    vertx.close().await()
  }
}

/**
 * Steep's main verticle
 * @author Michel Kraemer
 */
class Main : CoroutineVerticle() {
  override suspend fun start() {
    vertx.eventBus().registerCodec(LazyJsonObjectMessageCodec())
    vertx.eventBus().registerCodec(CompressedJsonObjectMessageCodec())

    PluginRegistryFactory.initialize(vertx)

    val pluginRegistry = PluginRegistryFactory.create()
    for (initializer in pluginRegistry.getInitializers()) {
      initializer.call(vertx)
    }

    val options = deploymentOptionsOf(config = config)

    if (config.getBoolean(ConfigConstants.CLOUD_ENABLED, false)) {
      vertx.deployVerticle(CloudManager::class.qualifiedName!!, options).await()
    }

    if (config.getBoolean(ConfigConstants.SCHEDULER_ENABLED, true)) {
      vertx.deployVerticle(Scheduler::class.qualifiedName!!, options).await()
    }

    if (config.getBoolean(ConfigConstants.CONTROLLER_ENABLED, true)) {
      vertx.deployVerticle(Controller::class.qualifiedName!!, options).await()
    }

    if (config.getBoolean(ConfigConstants.AGENT_ENABLED, true)) {
      val agentId = config.getString(ConfigConstants.AGENT_ID) ?:
          throw RuntimeException("Missing agentId")
      val instances = config.getInteger(ConfigConstants.AGENT_INSTANCES, 1)
      for (i in 1..instances) {
        val id = if (i == 1) agentId else "$agentId[$i]"
        val configWithAgentId = config.copy().put(ConfigConstants.AGENT_ID, id)
        val optionsWithAgentId = deploymentOptionsOf(config = configWithAgentId)
        vertx.deployVerticle(Steep::class.qualifiedName!!, optionsWithAgentId).await()
      }
    }

    if (config.getBoolean(ConfigConstants.HTTP_ENABLED, true)) {
      vertx.deployVerticle(HttpEndpoint::class.qualifiedName!!, options).await()
    }

    if (config.getBoolean(ConfigConstants.GARBAGECOLLECTOR_ENABLED, false)) {
      vertx.deployVerticle(GarbageCollector::class.qualifiedName!!, options).await()
    }
  }
}
