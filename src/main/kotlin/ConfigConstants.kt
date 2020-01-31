/**
 * Configuration constants
 * @author Michel Kraemer
 */
object ConfigConstants {
  /**
   * The path to the files containing service metadata. Either a string
   * pointing to a single file or a glob (e.g. &#42;&#42;&#47;&#42;.yaml) or
   * an array of files or globs.
   */
  const val SERVICES = "steep.services"

  /**
   * The path to the file(s) containing rules for the [RuleSystem]. Either
   * a string pointing to a single file or a file pattern (e.g.
   * &#42;&#42;&#47;&#42;.yaml) or an array of files or globs.
   */
  const val RULES = "steep.rules"

  /**
   * The path to the file(s) containing plugin descriptors. Either
   * a string pointing to a single file or a file pattern (e.g.
   * &#42;&#42;&#47;&#42;.yaml) or an array of files or globs.
   */
  const val PLUGINS = "steep.plugins"

  /**
   * A file that keeps additional configuration (overrides the main
   * configuration). Note that configuration items in this file can still be
   * overridden with environment variables.
   */
  const val OVERRIDE_CONFIG_FILE = "steep.overrideConfigFile"

  /**
   * Path where temporary files should be stored
   */
  const val TMP_PATH = "steep.tmpPath"

  /**
   * Path where output files should be stored
   */
  const val OUT_PATH = "steep.outPath"

  /**
   * The IP address (or hostname) to bind the clustered eventbus to
   */
  const val CLUSTER_EVENTBUS_HOST = "steep.cluster.eventBus.host"

  /**
   * The port the clustered eventbus should listen on
   */
  const val CLUSTER_EVENTBUS_PORT = "steep.cluster.eventBus.port"

  /**
   * The IP address (or hostname) the eventbus uses to announce itself within
   * in the cluster
   */
  const val CLUSTER_EVENTBUS_PUBLIC_HOST = "steep.cluster.eventBus.publicHost"

  /**
   * The port that the eventbus uses to announce itself within in the cluster
   */
  const val CLUSTER_EVENTBUS_PUBLIC_PORT = "steep.cluster.eventBus.publicPort"

  /**
   * The IP address (or hostname) and port Hazelcast uses to announce itself
   * within in the cluster
   */
  const val CLUSTER_HAZELCAST_PUBLIC_ADDRESS = "steep.cluster.hazelcast.publicAddress"

  /**
   * The port that Hazelcast should listen on
   */
  const val CLUSTER_HAZELCAST_PORT = "steep.cluster.hazelcast.port"

  /**
   * A list of IP address patterns specifying valid interfaces Hazelcast
   * should bind to
   */
  const val CLUSTER_HAZELCAST_INTERFACES = "steep.cluster.hazelcast.interfaces"

  /**
   * A list of IP addresses (or hostnames) of Hazelcast cluster members
   */
  const val CLUSTER_HAZELCAST_MEMBERS = "steep.cluster.hazelcast.members"

  /**
   * `true` if Hazelcast should use TCP to connect to other instances, `false`
   * if it should use multicast
   */
  const val CLUSTER_HAZELCAST_TCPENABLED = "steep.cluster.hazelcast.tcpEnabled"

  /**
   * `true` if an HTTP server should be deployed
   */
  const val HTTP_ENABLED = "steep.http.enabled"

  /**
   * The host to bind the HTTP server to
   */
  const val HTTP_HOST = "steep.http.host"

  /**
   * The port the HTTP server should listen on
   */
  const val HTTP_PORT = "steep.http.port"

  /**
   * The maximum size of HTTP POST bodies in bytes
   */
  const val HTTP_POST_MAX_SIZE = "steep.http.postMaxSize"

  /**
   * The path where the HTTP endpoints should be mounted
   */
  const val HTTP_BASE_PATH = "steep.http.basePath"

  /**
   * The interval in which the controller looks for accepted submissions
   */
  const val CONTROLLER_LOOKUP_INTERVAL = "steep.controller.lookupIntervalMilliseconds"

  /**
   * The interval in which the controller looks for orphaned running
   * submissions (i.e. submissions that are in the status `RUNNING' but that
   * are currently not being processed by any [Controller])
   */
  const val CONTROLLER_LOOKUP_ORPHANS_INTERVAL = "steep.controller.lookupOrphansIntervalMilliseconds"

  /**
   * The interval in which the scheduler looks for registered process chains
   */
  const val SCHEDULER_LOOKUP_INTERVAL = "steep.scheduler.lookupIntervalMilliseconds"

  /**
   * `true` if this Steep instance should be able to execute process
   * chains through [agent.LocalAgent]
   */
  const val AGENT_ENABLED = "steep.agent.enabled"

  /**
   * Unique identifier of this agent instance
   */
  const val AGENT_ID = "steep.agent.id"

  /**
   * List of capabilities that this agent provides
   */
  const val AGENT_CAPABILTIIES = "steep.agent.capabilities"

  /**
   * The number of minutes an agent should remain idle until it shuts itself
   * down gracefully. By default, this value is `0`, which means the agent
   * never shuts itself down.
   */
  const val AGENT_AUTO_SHUTDOWN_TIMEOUT = "steep.agent.autoShutdownTimeoutMinutes"

  /**
   * The number of seconds that should pass before an idle agent decides
   * that it is not busy anymore
   */
  const val AGENT_BUSY_TIMEOUT = "steep.agent.busyTimeoutSeconds"

  /**
   * The number of output lines to collect at most from each executed service
   * (also applies to error output)
   */
  const val AGENT_OUTPUT_LINES_TO_COLLECT = "steep.agent.outputLinesToCollect"

  /**
   * Additional volume mounts to be passed to the Docker runtime
   */
  const val RUNTIMES_DOCKER_VOLUMES= "steep.runtimes.docker.volumes"

  /**
   * The database driver (see [db.SubmissionRegistryFactory] for valid values)
   */
  const val DB_DRIVER = "steep.db.driver"

  /**
   * The database URL
   */
  const val DB_URL = "steep.db.url"

  /**
   * The database username
   */
  const val DB_USERNAME = "steep.db.username"

  /**
   * The database password
   */
  const val DB_PASSWORD = "steep.db.password"

  /**
   * `true` if Steep should connect to a cloud to acquire remote
   * agents on demand
   */
  const val CLOUD_ENABLED = "steep.cloud.enabled"

  /**
   * Defines which [cloud.CloudClient] to use
   */
  const val CLOUD_DRIVER = "steep.cloud.driver"

  /**
   * A tag that should be attached to virtual machines to indicate that they
   * have been created by Steep
   */
  const val CLOUD_CREATED_BY_TAG = "steep.cloud.createdByTag"

  /**
   * The path to the file that describes all available setups
   */
  const val CLOUD_SETUPS_FILE = "steep.cloud.setupsFile"

  /**
   * The number of seconds that should pass before the Cloud manager syncs
   * its internal state with the Cloud again
   */
  const val CLOUD_SYNC_INTERVAL = "steep.cloud.syncIntervalSeconds"

  /**
   * OpenStack authentication endpoint
   */
  const val CLOUD_OPENSTACK_ENDPOINT = "steep.cloud.openstack.endpoint"

  /**
   * OpenStack username used for authentication
   */
  const val CLOUD_OPENSTACK_USERNAME = "steep.cloud.openstack.username"

  /**
   * OpenStack password used for authentication
   */
  const val CLOUD_OPENSTACK_PASSWORD = "steep.cloud.openstack.password"

  /**
   * OpenStack domain name used for authentication
   */
  const val CLOUD_OPENSTACK_DOMAIN_NAME = "steep.cloud.openstack.domainName"

  /**
   * The ID of the OpenStack project to connect to.Either
   * [CLOUD_OPENSTACK_PROJECT_ID] or [CLOUD_OPENSTACK_PROJECT_NAME] must be set
   * but not both at the same time.
   */
  const val CLOUD_OPENSTACK_PROJECT_ID = "steep.cloud.openstack.projectId"

  /**
   * The name of the OpenStack project to connect to. Will be used in
   * combination with [CLOUD_OPENSTACK_DOMAIN_NAME] if
   * [CLOUD_OPENSTACK_PROJECT_ID] is not set.
   */
  const val CLOUD_OPENSTACK_PROJECT_NAME = "steep.cloud.openstack.projectName"

  /**
   * The OpenStack availability zone in which to create resources
   */
  const val CLOUD_OPENSTACK_AVAILABILITY_ZONE = "steep.cloud.openstack.availabilityZone"

  /**
   * The ID of the OpenStack network to attach new VMs to
   */
  const val CLOUD_OPENSTACK_NETWORK_ID = "steep.cloud.openstack.networkId"

  /**
   * `true` if new VMs should have a public IP address
   */
  const val CLOUD_OPENSTACK_USE_PUBLIC_IP = "steep.cloud.openstack.usePublicIp"

  /**
   * The OpenStack security groups that new VMs should be put in
   */
  const val CLOUD_OPENSTACK_SECURITY_GROUPS = "steep.cloud.openstack.securityGroups"

  /**
   * The OpenStack keypair to deploy to new VMs
   */
  const val CLOUD_OPENSTACK_KEYPAIR_NAME = "steep.cloud.openstack.keypairName"

  /**
   * Username for SSH access to VMs
   */
  const val CLOUD_SSH_USERNAME = "steep.cloud.ssh.username"

  /**
   * Location of a private key to use for SSH
   */
  const val CLOUD_SSH_PRIVATE_KEY_LOCATION = "steep.cloud.ssh.privateKeyLocation"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
