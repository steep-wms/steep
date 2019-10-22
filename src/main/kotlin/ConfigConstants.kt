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
  const val SERVICES = "jobmanager.services"

  /**
   * The path to the file(s) containing rules for the [RuleSystem]. Either
   * a string pointing to a single file or a file pattern (e.g.
   * &#42;&#42;&#47;&#42;.yaml) or an array of files or globs.
   */
  const val RULES = "jobmanager.rules"

  /**
   * The path to the file(s) containing plugin descriptors. Either
   * a string pointing to a single file or a file pattern (e.g.
   * &#42;&#42;&#47;&#42;.yaml) or an array of files or globs.
   */
  const val PLUGINS = "jobmanager.plugins"

  /**
   * A file that keeps additional configuration (overrides the main
   * configuration). Note that configuration items in this file can still be
   * overridden with environment variables.
   */
  const val OVERRIDE_CONFIG_FILE = "jobmanager.overrideConfigFile"

  /**
   * Path where temporary files should be stored
   */
  const val TMP_PATH = "jobmanager.tmpPath"

  /**
   * Path where output files should be stored
   */
  const val OUT_PATH = "jobmanager.outPath"

  /**
   * The IP address (or hostname) to bind the clustered eventbus to
   */
  const val CLUSTER_EVENTBUS_HOST = "jobmanager.cluster.eventBus.host"

  /**
   * The port the clustered eventbus should listen on
   */
  const val CLUSTER_EVENTBUS_PORT = "jobmanager.cluster.eventBus.port"

  /**
   * The IP address (or hostname) the eventbus uses to announce itself within
   * in the cluster
   */
  const val CLUSTER_EVENTBUS_PUBLIC_HOST = "jobmanager.cluster.eventBus.publicHost"

  /**
   * The port that the eventbus uses to announce itself within in the cluster
   */
  const val CLUSTER_EVENTBUS_PUBLIC_PORT = "jobmanager.cluster.eventBus.publicPort"

  /**
   * The IP address (or hostname) and port Hazelcast uses to announce itself
   * within in the cluster
   */
  const val CLUSTER_HAZELCAST_PUBLIC_ADDRESS = "jobmanager.cluster.hazelcast.publicAddress"

  /**
   * The port that Hazelcast should listen on
   */
  const val CLUSTER_HAZELCAST_PORT = "jobmanager.cluster.hazelcast.port"

  /**
   * A list of IP address patterns specifying valid interfaces Hazelcast
   * should bind to
   */
  const val CLUSTER_HAZELCAST_INTERFACES = "jobmanager.cluster.hazelcast.interfaces"

  /**
   * A list of IP addresses (or hostnames) of Hazelcast cluster members
   */
  const val CLUSTER_HAZELCAST_MEMBERS = "jobmanager.cluster.hazelcast.members"

  /**
   * `true` if Hazelcast should use TCP to connect to other instances, `false`
   * if it should use multicast
   */
  const val CLUSTER_HAZELCAST_TCPENABLED = "jobmanager.cluster.hazelcast.tcpEnabled"

  /**
   * `true` the the JobManager should deploy an HTTP server
   */
  const val HTTP_ENABLED = "jobmanager.http.enabled"

  /**
   * The host to bind the JobManager's HTTP server to
   */
  const val HTTP_HOST = "jobmanager.http.host"

  /**
   * The port the JobManager's HTTP server should listen on
   */
  const val HTTP_PORT = "jobmanager.http.port"

  /**
   * The maximum size of HTTP POST bodies in bytes
   */
  const val HTTP_POST_MAX_SIZE = "jobmanager.http.postMaxSize"

  /**
   * The path where the JobManager endpoints should be mounted
   */
  const val HTTP_BASE_PATH = "jobmanager.http.basePath"

  /**
   * The interval in which the controller looks for accepted submissions
   */
  const val CONTROLLER_LOOKUP_INTERVAL = "jobmanager.controller.lookupIntervalMilliseconds"

  /**
   * The interval in which the controller looks for orphaned running
   * submissions (i.e. submissions that are in the status `RUNNING' but that
   * are currently not being processed by any [Controller])
   */
  const val CONTROLLER_LOOKUP_ORPHANS_INTERVAL = "jobmanager.controller.lookupOrphansIntervalMilliseconds"

  /**
   * The interval in which the scheduler looks for registered process chains
   */
  const val SCHEDULER_LOOKUP_INTERVAL = "jobmanager.scheduler.lookupIntervalMilliseconds"

  /**
   * `true` if this JobManager instance should be able to execute process
   * chains through [agent.LocalAgent]
   */
  const val AGENT_ENABLED = "jobmanager.agent.enabled"

  /**
   * Unique identifier of this agent instance
   */
  const val AGENT_ID = "jobmanager.agent.id"

  /**
   * List of capabilities that this agent provides
   */
  const val AGENT_CAPABILTIIES = "jobmanager.agent.capabilities"

  /**
   * The number of minutes an agent should remain idle until it shuts itself
   * down gracefully. By default, this value is `0`, which means the agent
   * never shuts itself down.
   */
  const val AGENT_AUTO_SHUTDOWN_TIMEOUT = "jobmanager.agent.autoShutdownTimeoutMinutes"

  /**
   * The number of seconds that should pass before an idle agent decides
   * that it is not busy anymore
   */
  const val AGENT_BUSY_TIMEOUT = "jobmanager.agent.busyTimeoutSeconds"

  /**
   * The number of output lines to collect at most from each executed service
   * (also applies to error output)
   */
  const val AGENT_OUTPUT_LINES_TO_COLLECT = "jobmanager.agent.outputLinesToCollect"

  /**
   * Additional volume mounts to be passed to the Docker runtime
   */
  const val RUNTIMES_DOCKER_VOLUMES= "jobmanager.runtimes.docker.volumes"

  /**
   * The database driver (see [db.SubmissionRegistryFactory] for valid values)
   */
  const val DB_DRIVER = "jobmanager.db.driver"

  /**
   * The database URL
   */
  const val DB_URL = "jobmanager.db.url"

  /**
   * The database username
   */
  const val DB_USERNAME = "jobmanager.db.username"

  /**
   * The database password
   */
  const val DB_PASSWORD = "jobmanager.db.password"

  /**
   * `true` if the JobManager should connect to a cloud to acquire remote
   * agents on demand
   */
  const val CLOUD_ENABLED = "jobmanager.cloud.enabled"

  /**
   * Defines which [cloud.CloudClient] to use
   */
  const val CLOUD_DRIVER = "jobmanager.cloud.driver"

  /**
   * A tag that should be attached to virtual machines to indicate that they
   * have been created by the JobManager
   */
  const val CLOUD_CREATED_BY_TAG = "jobmanager.cloud.createdByTag"

  /**
   * The path to the file that describes all available setups
   */
  const val CLOUD_SETUPS_FILE = "jobmanager.cloud.setupsFile"

  /**
   * The number of seconds that should pass before the Cloud manager syncs
   * its internal state with the Cloud again
   */
  const val CLOUD_SYNC_INTERVAL = "jobmanager.cloud.syncIntervalSeconds"

  /**
   * OpenStack authentication endpoint
   */
  const val CLOUD_OPENSTACK_ENDPOINT = "jobmanager.cloud.openstack.endpoint"

  /**
   * OpenStack username used for authentication
   */
  const val CLOUD_OPENSTACK_USERNAME = "jobmanager.cloud.openstack.username"

  /**
   * OpenStack password used for authentication
   */
  const val CLOUD_OPENSTACK_PASSWORD = "jobmanager.cloud.openstack.password"

  /**
   * OpenStack domain name used for authentication
   */
  const val CLOUD_OPENSTACK_DOMAIN_NAME = "jobmanager.cloud.openstack.domainName"

  /**
   * The ID of the OpenStack project to connect to.Either
   * [CLOUD_OPENSTACK_PROJECT_ID] or [CLOUD_OPENSTACK_PROJECT_NAME] must be set
   * but not both at the same time.
   */
  const val CLOUD_OPENSTACK_PROJECT_ID = "jobmanager.cloud.openstack.projectId"

  /**
   * The name of the OpenStack project to connect to. Will be used in
   * combination with [CLOUD_OPENSTACK_DOMAIN_NAME] if
   * [CLOUD_OPENSTACK_PROJECT_ID] is not set.
   */
  const val CLOUD_OPENSTACK_PROJECT_NAME = "jobmanager.cloud.openstack.projectName"

  /**
   * The OpenStack availability zone in which to create resources
   */
  const val CLOUD_OPENSTACK_AVAILABILITY_ZONE = "jobmanager.cloud.openstack.availabilityZone"

  /**
   * The ID of the OpenStack network to attach new VMs to
   */
  const val CLOUD_OPENSTACK_NETWORK_ID = "jobmanager.cloud.openstack.networkId"

  /**
   * `true` if new VMs should have a public IP address
   */
  const val CLOUD_OPENSTACK_USE_PUBLIC_IP = "jobmanager.cloud.openstack.usePublicIp"

  /**
   * The OpenStack security groups that new VMs should be put in
   */
  const val CLOUD_OPENSTACK_SECURITY_GROUPS = "jobmanager.cloud.openstack.securityGroups"

  /**
   * The OpenStack keypair to deploy to new VMs
   */
  const val CLOUD_OPENSTACK_KEYPAIR_NAME = "jobmanager.cloud.openstack.keypairName"

  /**
   * Username for SSH access to VMs
   */
  const val CLOUD_SSH_USERNAME = "jobmanager.cloud.ssh.username"

  /**
   * Location of a private key to use for SSH
   */
  const val CLOUD_SSH_PRIVATE_KEY_LOCATION = "jobmanager.cloud.ssh.privateKeyLocation"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
