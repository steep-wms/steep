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
   * The path to the file(s) containing plugin descriptors. Either
   * a string pointing to a single file or a file pattern (e.g.
   * &#42;&#42;&#47;&#42;.yaml) or an array of files or globs.
   */
  const val PLUGINS = "steep.plugins"

  /**
   * The path to the files containing macros. Either a string pointing to a
   * single file or a glob (e.g. &#42;&#42;&#47;&#42;.yaml) or an array of
   * files or globs.
   */
  const val MACROS = "steep.macros"

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
   * An optional cluster name that can be used to separate clusters of Steep
   * instances. Two instances from different clusters (with different names)
   * cannot connect to each other. By default, no cluster name is set, which
   * means all instances can connect to each other. However, a Steep instance
   * without a cluster name cannot connect to a named cluster.
   *
   * **Heads up:** if have a cluster name set and you're using the
   * [cloud.CloudManager] to deploy remote agents on demand, make sure these
   * Steep instances use the same cluster name. Otherwise, you won't be able to
   * connect to them.
   */
  const val CLUSTER_HAZELCAST_CLUSTER_NAME = "steep.cluster.hazelcast.clusterName"

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
   * `true` if the IP addresses from potential Hazelcast cluster members should
   * be restored on startup from the [db.VMRegistry] (i.e. VMs that are still
   * running will automatically be added to the list of members)
   */
  const val CLUSTER_HAZELCAST_RESTORE_MEMBERS_ENABLED =
      "steep.cluster.hazelcast.restoreMembersOnStartup.enabled"

  /**
   * If [CLUSTER_HAZELCAST_RESTORE_MEMBERS_ENABLED] is `true`, potential
   * Hazelcast members will be restored from the [db.VMRegistry]. This
   * configuration item specifies on which Hazelcast port these members are
   * listening.
   */
  const val CLUSTER_HAZELCAST_RESTORE_MEMBERS_DEFAULT_PORT =
      "steep.cluster.hazelcast.restoreMembersOnStartup.defaultPort"

  /**
   * An optional name specifying in which group this Hazelcast member should
   * be placed. Steep uses distributed maps ([com.hazelcast.map.IMap]) to share
   * data between instances. Data in these maps is partitioned (i.e. distributed
   * to the individual cluster members). In a large cluster, no member keeps
   * all the data. Most nodes only keep a small fraction of the data (a
   * partition).
   *
   * To make sure data is not lost if a member goes down, Hazelcast uses
   * backups to distribute copies of the data across the cluster. By specifying
   * a placement group, you can control how Hazelcast distributes these backups.
   * Hazelcast will always prefer creating backups in a group that does not own
   * the data so that if all members of a group go down, the other group still
   * has all the backup data.
   *
   * Examples for sensible groups are racks, data centers, or availability zones.
   *
   * For more information, see the following links:
   *
   * * https://docs.hazelcast.com/hazelcast/5.1/architecture/data-partitioning
   * * https://docs.hazelcast.com/hazelcast/5.1/clusters/partition-group-configuration
   * * https://docs.hazelcast.com/hazelcast/5.1/data-structures/backing-up-maps
   *
   * Note that if you configure a placement group name, all members in your
   * cluster must also have a placement group name. Otherwise, you will
   * receive an exception about mismatching configuration on startup.
   */
  const val CLUSTER_HAZELCAST_PLACEMENT_GROUP_NAME =
      "steep.cluster.hazelcast.placementGroupName"

  /**
   * `true` if this instance should be a [Hazelcast lite member](https://docs.hazelcast.com/hazelcast/5.1/maintain-cluster/lite-members).
   * Lite members do not own any in-memory data. They are mainly used for
   * compute-intensive tasks. With regard to Steep, an instance with a
   * [Controller] and a [Scheduler] should not be a lite member, because these
   * components heavily rely on internal state. A Steep instance that only
   * contains an [agent.Agent] and therefore only executes services, however,
   * could be a lite member.
   *
   * Your cluster cannot consist of only lite members. Otherwise, it is not
   * able to maintain internal state at all.
   *
   * Note that since lite members cannot keep data, they are not suitable to
   * keep backups either. See [CLUSTER_HAZELCAST_PLACEMENT_GROUP_NAME] for more
   * information. For reasons of reliability, a cluster should contain at least
   * three full (i.e. non-lite) members.
   */
  const val CLUSTER_HAZELCAST_LITE_MEMBER = "steep.cluster.hazelcast.liteMember"

  /**
   * `true` if split-brain protection should be enabled. This mechanism
   * makes sure the cluster is only able to operate if there are at least
   * n members, where n is defined by [CLUSTER_HAZELCAST_SPLITBRAINPROTECTION_MINCLUSTERSIZE].
   * If there are less than n members, Steep instances in the cluster will
   * not be able to access cluster-wide data structures and stop to operate
   * until the issue has been resolved.
   *
   * This mechanism protects against so-called split-brain situations where one
   * part of the cluster loses connection to another part and is therefore
   * split into two (or multiple) parts. If one part becomes too small, it
   * should stop to operate to avoid doing any harm.
   *
   * See [the Hazelcast documentation](https://docs.hazelcast.com/imdg/4.2/network-partitioning/split-brain-protection)
   * for more information.
   */
  const val CLUSTER_HAZELCAST_SPLITBRAINPROTECTION_ENABLED =
      "steep.cluster.hazelcast.splitBrainProtection.enabled"

  /**
   * The minimum number of members the cluster must have to be able operate.
   *
   * Recommendations: Your cluster should have an odd number of members. The
   * minimum cluster size should be even and represent the majority of your
   * cluster. For example, if your cluster has 7 nodes, set this value to 4.
   * This makes sure that when a split-brain situation happens, the majority
   * of your cluster will be able to continue operating while the smaller part
   * will stop.
   */
  const val CLUSTER_HAZELCAST_SPLITBRAINPROTECTION_MINCLUSTERSIZE =
      "steep.cluster.hazelcast.splitBrainProtection.minClusterSize"

  /**
   * `true` if the split-brain protection mechanism should only start to
   * be in effect once the cluster has reached its minimum size. This allows
   * the cluster to startup gracefully even if the member count is
   * temporarily lower than the defined minimum.
   */
  const val CLUSTER_HAZELCAST_SPLITBRAINPROTECTION_GRACEFULSTARTUP =
      "steep.cluster.hazelcast.splitBrainProtection.gracefulStartup"

  /**
   * An optional timeout specifying how long a Steep instance may keep running
   * after a split-brain situation has been detected. When the timeout is
   * reached and the split-brain situation has not been resolved in the
   * meantime, the Steep instance shuts itself down with exit code 16. This
   * mechanism can be used to prevent a Steep instance from doing any harm when
   * it is in a split-brain situation.
   */
  const val CLUSTER_HAZELCAST_SPLITBRAINPROTECTION_EXITPROCESSAFTER =
      "steep.cluster.hazelcast.splitBrainProtection.exitProcessAfter"

  /**
   * `true` if Hazelcast should try to automatically discover other members in
   * a Kubernetes cluster
   */
  const val CLUSTER_HAZELCAST_KUBERNETES_ENABLED = "steep.cluster.kubernetes.enabled"

  /**
   * The Kubernetes namespace where Steep is running. Falls back to the values
   * of the environment variables `KUBERNETES_NAMESPACE` or
   * `OPENSHIFT_BUILD_NAMESPACE`, or to the pod's namespace retrieved from
   * `/var/run/secrets/kubernetes.io/serviceaccount/namespace`.
   */
  const val CLUSTER_HAZELCAST_KUBERNETES_NAMESPACE = "steep.cluster.kubernetes.namespace"

  /**
   * An optional service name to limit the cluster members to pods that are
   * connected to a given service. If not specified, all pods in the namespace
   * will be considered potential cluster members.
   */
  const val CLUSTER_HAZELCAST_KUBERNETES_SERVICE_NAME = "steep.cluster.kubernetes.serviceName"

  /**
   * Instead of specifying a namespace and a service name through
   * [CLUSTER_HAZELCAST_KUBERNETES_NAMESPACE] and
   * [CLUSTER_HAZELCAST_KUBERNETES_SERVICE_NAME], you can also specify a DNS
   * name in the form `SERVICE-NAME.NAMESPACE.svc.cluster.local`. Hazelcast
   * will perform a DNS lookup to obtain the pod IP addresses of cluster
   * members.
   */
  const val CLUSTER_HAZELCAST_KUBERNETES_SERVICE_DNS = "steep.cluster.kubernetes.serviceDns"

  /**
   * The interval at which the [Main] thread looks for orphaned entries in the
   * remote agent registry. Such entries may happen if there is a network
   * failure during deregistration of an agent. The interval is specified
   * as a human-readable duration (see [helper.toDuration]).
   */
  const val CLUSTER_LOOKUP_ORPHANS_INTERVAL = "steep.cluster.lookupOrphansInterval"

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
   * A regular expression for a whitelist of enabled routes on the HTTP server
   */
  const val HTTP_ALLOW_ROUTES = "steep.http.allowRoutes"

  /**
   * `true` if Cross-Origin Resource Sharing (CORS) should be enabled
   */
  const val HTTP_CORS_ENABLE = "steep.http.cors.enable"

  /**
   * A regular expression specifying allowed CORS origins. Use `*` to allow all
   * origins.
   */
  const val HTTP_CORS_ALLOW_ORIGIN = "steep.http.cors.allowOrigin"

  /**
   * `true` if the Access-Control-Allow-Credentials` response header should
   * be returned.
   */
  const val HTTP_CORS_ALLOW_CREDENTIALS = "steep.http.cors.allowCredentials"

  /**
   * A string or an array indicating which header field names can be used
   * in a request.
   */
  const val HTTP_CORS_ALLOW_HEADERS = "steep.http.cors.allowHeaders"

  /**
   * A string or an array indicating which HTTP methods can be used in a
   * request.
   */
  const val HTTP_CORS_ALLOW_METHODS = "steep.http.cors.allowMethods"

  /**
   * A string or an array indicating which headers are safe to expose to the
   * API of a CORS API specification.
   */
  const val HTTP_CORS_EXPOSE_HEADERS = "steep.http.cors.exposeHeaders"

  /**
   * The number of seconds the results of a preflight request can be cached in
   * a preflight result cache.
   */
  const val HTTP_CORS_MAX_AGE_SECONDS = "steep.http.cors.maxAgeSeconds"

  /**
   * `true` if the controller should be enabled. Set this value to `false` if
   * your Steep instance does not have access to the shared database.
   */
  const val CONTROLLER_ENABLED = "steep.controller.enabled"

  /**
   * The interval at which the controller looks for accepted submissions.
   * Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val CONTROLLER_LOOKUP_INTERVAL = "steep.controller.lookupInterval"

  /**
   * The maximum number of errors to tolerate when looking up the status
   * of process chains of running submissions
   */
  const val CONTROLLER_LOOKUP_MAXERRORS = "steep.controller.lookupMaxErrors"

  /**
   * The interval at which the controller looks for orphaned running
   * submissions (i.e. submissions that are in the status `RUNNING' but that
   * are currently not being processed by any [Controller]). Specified as a
   * human-readable duration (see [helper.toDuration]).
   */
  const val CONTROLLER_LOOKUP_ORPHANS_INTERVAL = "steep.controller.lookupOrphansInterval"

  /**
   * The time the controller should wait after startup before it looks for
   * orphaned running submissions for the first time. This property is useful
   * if you want to implement a rolling update from one Steep instance to
   * another. Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val CONTROLLER_LOOKUP_ORPHANS_INITIAL_DELAY = "steep.controller.lookupOrphansInitialDelay"

  /**
   * `true` if the scheduler should be enabled. Set this value to `false` if
   * your Steep instance does not have access to the shared database.
   */
  const val SCHEDULER_ENABLED = "steep.scheduler.enabled"

  /**
   * The interval at which the scheduler looks for registered process chains.
   * Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val SCHEDULER_LOOKUP_INTERVAL = "steep.scheduler.lookupInterval"

  /**
   * The interval at which the scheduler looks for orphaned running
   * process chains (i.e. process chains that are in the status `RUNNING' but
   * that are currently not being processed by any [Scheduler]). Note that
   * the scheduler also always looks for orphaned process chains when it detects
   * that another scheduler instance has just left the cluster (regardless of
   * the configured interval). The interval is specified as a human-readable
   * duration (see [helper.toDuration]).
   */
  const val SCHEDULER_LOOKUP_ORPHANS_INTERVAL = "steep.scheduler.lookupOrphansInterval"

  /**
   * The time the scheduler should wait after startup before it looks for
   * orphaned running process chains for the first time. This property is
   * useful if you want to implement a rolling update from one Steep instance
   * to another. Note that the scheduler also looks for orphaned process chains
   * when another scheduler instance has just left the cluster, even if the
   * initial delay has not passed by yet. The time is specified as a
   * human-readable duration (see [helper.toDuration]).
   */
  const val SCHEDULER_LOOKUP_ORPHANS_INITIAL_DELAY = "steep.scheduler.lookupOrphansInitialDelay"

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
   * The number of instances to create of this agent (i.e. how many times it
   * should be deployed in the same JVM). Defaults to `1`.
   */
  const val AGENT_INSTANCES = "steep.agent.instances"

  /**
   * The time an agent should remain idle until it shuts itself down
   * gracefully. By default, this value is `0`, which means the agent never
   * shuts itself down. Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val AGENT_AUTO_SHUTDOWN_TIMEOUT = "steep.agent.autoShutdownTimeout"

  /**
   * The time that should pass before an idle agent decides that it is not
   * busy anymore. Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val AGENT_BUSY_TIMEOUT = "steep.agent.busyTimeout"

  /**
   * The number of output lines to collect at most from each executed service
   * (also applies to error output)
   */
  const val AGENT_OUTPUT_LINES_TO_COLLECT = "steep.agent.outputLinesToCollect"

  /**
   * Additional environment variables to be passed to the Docker runtime
   */
  const val RUNTIMES_DOCKER_ENV = "steep.runtimes.docker.env"

  /**
   * Additional volume mounts to be passed to the Docker runtime
   */
  const val RUNTIMES_DOCKER_VOLUMES = "steep.runtimes.docker.volumes"

  /**
   * Defines if the Docker runtime should pull the image before running a
   * container. Possible values:
   *
   * * `always` - Always pull the image before running, regardless of whether
   *   it is already present locally or not.
   * * `missing` - Only pull the image if it is not present locally.
   * * `never` - Never pull the image. If it is not present locally, the
   *   operation will fail.
   * * `auto` - Automatically decide whether to pull the image or not based on
   *   the following criteria (in this order):
   *     * If an image digest is given, the Docker runtime will behave as if
   *       the value were equal to `missing`
   *     * If an image tag is given that is not `latest`, the Docker runtime
   *       will behave as if the value were equal to `missing`
   *     * If an image tag is given and it is `latest`, the Docker runtime
   *       will behave as if the value was `always`
   *     * If no image tag is given, the Docker runtime will behave as if the
   *       value was `always`
   *
   * The default value is `auto`.
   */
  const val RUNTIMES_DOCKER_PULL = "steep.runtimes.docker.pull"

  /**
   * The namespace in which Steep should create Kubernetes jobs and pods
   */
  const val RUNTIMES_KUBERNETES_NAMESPACE = "steep.runtimes.kubernetes.namespace"

  /**
   * An optional list of environment variables that should be injected into the
   * containers started by the Kubernetes runtime. The list items should be
   * Kubernetes environment variable objects. See the
   * [Kubernetes API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#envvar-v1-core)
   * for more information. Example:
   *
   * ```yaml
   * env:
   *   - name: MYVAR
   *     value: something
   * ```
   */
  const val RUNTIMES_KUBERNETES_ENV = "steep.runtimes.kubernetes.env"

  /**
   * An optional list of Kubernetes volume mount objects. The Kubernetes runtime
   * mounts volumes specified here into the started containers. For example,
   * the following configuration mounts a volume with the name `steep-tmp-path`
   * to the path `/tmp/steep/tmp` inside the container:
   *
   * ```yaml
   * volumeMounts:
   *   - name: steep-tmp-path
   *     mountPath: /tmp/steep/tmp
   * ```
   *
   * The volume `steep-tmp-path` must be defined through the configuration item
   * [RUNTIMES_KUBERNETES_VOLUMES].
   *
   * See the [Kubernetes API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#volumemount-v1-core)
   * for more information about volume mount objects.
   */
  const val RUNTIMES_KUBERNETES_VOLUMEMOUNTS = "steep.runtimes.kubernetes.volumeMounts"

  /**
   * An optional list of Kubernetes volumes. The Kubernetes runtime attaches
   * these volumes to the started pods. For example, the following configuration
   * defines a volume that refers to a host path:
   *
   * ```yaml
   * volumes:
   *   - name: steep-tmp-path
   *     hostPath:
   *       path: /path/to/steep/tmp_path
   * ```
   *
   * You can also refer to persistent volume claims:
   *
   * ```yaml
   * volumes:
   *   - name: steep-tmp-path
   *     persistentVolumeClaim:
   *       claimName: steep-tmp-path-volume-claim
   * ```
   *
   * See the [Kubernetes API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#volume-v1-core)
   * for more information about volumes.
   */
  const val RUNTIMES_KUBERNETES_VOLUMES = "steep.runtimes.kubernetes.volumes"

  /**
   * The image pull policy for any job the Kubernetes runtime starts. See the
   * [Kubernetes documentation](https://kubernetes.io/docs/concepts/containers/images/#updating-images)
   * or the `imagePullPolicy` parameter of the `Container` object in the
   * [Kubernetes API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#container-v1-core)
   * for more information.
   */
  const val RUNTIMES_KUBERNETES_IMAGEPULLPOLICY = "steep.runtimes.kubernetes.imagePullPolicy"

  /**
   * A list of image pull secrets for any job the Kubernetes runtime starts. See the
   * [Kubernetes documentation](https://kubernetes.io/docs/concepts/containers/images#specifying-imagepullsecrets-on-a-pod)
   * or the
   * [Kubernetes API reference](https://kubernetes.io/docs/reference/generated/kubernetes-api/v1.30/#localobjectreference-v1-core)
   * for more information.
   */
  const val RUNTIMES_KUBERNETES_IMAGEPULLSECRETS = "steep.runtimes.kubernetes.imagePullSecrets"

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
   * The maximum number of connections to keep in the pool
   */
  const val DB_CONNECTIONPOOL_MAXSIZE = "steep.db.connectionPool.maxSize"

  /**
   * The maximum time an idle connection should be kept in the pool before it
   * is closed
   */
  const val DB_CONNECTIONPOOL_MAXIDLETIME = "steep.db.connectionPool.maxIdleTime"

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
  const val CLOUD_SETUPS_FILE = "steep.cloud.setups.file"

  /**
   * The path to the file that describes all available setups
   */
  @Deprecated("Use 'steep.cloud.setups.file' instead")
  const val CLOUD_SETUPS_FILE_DEPRECATED = "steep.cloud.setupsFile"

  /**
   * Configuration items that describe a policy to control how VMs are created
   * from individual setups (see [model.setup.CreationPolicy]). The policy can
   * be overridden per [model.setup.Setup].
   *
   * We need to define individual keys here because it must be possible to
   * override each item with environment variables.
   */
  const val CLOUD_SETUPS_CREATION_RETRIES_MAXATTEMPTS = "steep.cloud.setups.creation.retries.maxAttempts"
  const val CLOUD_SETUPS_CREATION_RETRIES_DELAY = "steep.cloud.setups.creation.retries.delay"
  const val CLOUD_SETUPS_CREATION_RETRIES_EXPONENTIALBACKOFF = "steep.cloud.setups.creation.retries.exponentialBackoff"
  const val CLOUD_SETUPS_CREATION_RETRIES_MAXDELAY = "steep.cloud.setups.creation.retries.maxDelay"
  const val CLOUD_SETUPS_CREATION_LOCKAFTERRETRIES = "steep.cloud.setups.creation.lockAfterRetries"

  /**
   * The time that should pass before the Cloud manager syncs its internal
   * state with the Cloud again. Specified as a human-readable duration
   * (see [helper.toDuration]).
   */
  const val CLOUD_SYNC_INTERVAL = "steep.cloud.syncInterval"

  /**
   * The time that should pass before the Cloud manager sends keep-alive
   * messages to a minimum of remote agents again (so that they do not shut
   * down themselves). See [model.setup.Setup.minVMs]. Specified as a
   * human-readable duration (see [helper.toDuration]).
   */
  const val CLOUD_KEEP_ALIVE_INTERVAL = "steep.cloud.keepAliveInterval"

  /**
   * The maximum time the cloud manager should try to log in to a new VM via
   * SSH. The cloud manager will make a login attempt every 2 seconds until it
   * is successful or until the maximum duration has passed, in which case it
   * will destroy the VM. Specified as a human-readable duration (see
   * [helper.toDuration]).
   */
  const val CLOUD_TIMEOUTS_SSHREADY = "steep.cloud.timeouts.sshReady"

  /**
   * The maximum time the cloud manager should wait for an agent on a new VM to
   * become available (i.e. how long a new Steep instance may take to register
   * with the cluster) before it destroys the VM again. Specified as a
   * human-readable duration (see [helper.toDuration]).
   */
  const val CLOUD_TIMEOUTS_AGENTREADY = "steep.cloud.timeouts.agentReady"

  /**
   * The maximum time that creating a VM may take before it is aborted with
   * an error. Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val CLOUD_TIMEOUTS_CREATEVM = "steep.cloud.timeouts.createVM"

  /**
   * The maximum time that destroying a VM may take before it is aborted with
   * an error. Specified as a human-readable duration (see [helper.toDuration]).
   */
  const val CLOUD_TIMEOUTS_DESTROYVM = "steep.cloud.timeouts.destroyVM"

  /**
   * The maximum time each individual provisioning step (i.e. executing a
   * provisioning script or uploading files) may take before it is aborted.
   * Running provisioning commands will be killed after this timeout regardless
   * of whether they are still active or not. Specified as a human-readable
   * duration (see [helper.toDuration]).
   */
  const val CLOUD_TIMEOUTS_PROVISIONING = "steep.cloud.timeouts.provisioning"

  /**
   * Describes parameters of remote agents the CloudManager maintains in its pool
   */
  const val CLOUD_AGENTPOOL = "steep.cloud.agentPool"

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
   * The default log level for all loggers (one of `TRACE`, `DEBUG`, `INFO`,
   * `WARN`, `ERROR`, `OFF`). The default value is `DEBUG`.
   */
  const val LOGS_LEVEL = "steep.logs.level"

  /**
   * `true` if logging to the main log file should be enabled. The default
   * value is `false`.
   */
  const val LOGS_MAIN_ENABLED = "steep.logs.main.enabled"

  /**
   * The name of the main log file. The default is `logs/steep.log`.
   */
  const val LOGS_MAIN_LOGFILE = "steep.logs.main.logFile"

  /**
   * `true` if log files should be renamed every day. The file name of old
   * logs will be based on the main log file name [LOGS_MAIN_LOGFILE] and the
   * file's date in the form `YYYY-MM-DD` (e.g. `steep.2020-11-19.log`). The
   * default value is `true`.
   */
  const val LOGS_MAIN_DAILYROLLOVER_ENABLED = "steep.logs.main.dailyRollover.enabled"

  /**
   * The maximum number of days' worth of log files to keep. The default
   * value is `7`.
   */
  const val LOGS_MAIN_DAILYROLLOVER_MAXDAYS = "steep.logs.main.dailyRollover.maxDays"

  /**
   * The total maximum size of all log files. Oldest log files will deleted
   * when this size is reached. The default value is `104857600` (= 100 MB)
   */
  const val LOGS_MAIN_DAILYROLLOVER_MAXSIZE = "steep.logs.main.dailyRollover.maxSize"

  /**
   * `true` if the output of process chains should be logged separately to disk.
   * The output will still also appear on the console and in the main log file
   * (if enabled), but there, it's not separated by process chain. This feature
   * is useful if you want to record the output of individual process chains
   * and make it available through the `/logs/processchains` HTTP endpoint.
   * The default value is `false`.
   */
  const val LOGS_PROCESSCHAINS_ENABLED = "steep.logs.processChains.enabled"

  /**
   * The path where process chain logs will be stored. Individual files will
   * will be named after the ID of the corresponding process chain (e.g.
   * `aprsqz6d5f4aiwsdzbsq.log`). The default value is `logs/processchains`.
   */
  const val LOGS_PROCESSCHAINS_PATH = "steep.logs.processChains.path"

  /**
   * Set this configuration item to a value greater than `0` to group process
   * chain log files by prefix in subdirectories under the directory configured
   * through [LOGS_PROCESSCHAINS_PATH]. For example, if this configuration
   * item is set to `3`, Steep will create a separate subdirectory for all
   * process chains whose ID starts with the same three characters. The name of
   * this subdirectory will be these three characters. The process chains
   * `apomaokjbk3dmqovemwa` and `apomaokjbk3dmqovemsq` will be put into a
   * subdirectory called `apo`, and the process chain `ao344a53oyoqwhdelmna`
   * will be put into `ao3`. Note that in practice, `3` is a reasonable value,
   * which will create a new directory about every day. A value of `0` disables
   * grouping. The default value is `0`.
   */
  const val LOGS_PROCESSCHAINS_GROUPBYPREFIX = "steep.logs.processChains.groupByPrefix"

  /**
   * `true` if the garbage collector should be enabled. The garbage collector
   * runs in the background and removes outdated objects from the registry
   * at the interval specified with [GARBAGECOLLECTOR_CRON]
   */
  const val GARBAGECOLLECTOR_ENABLED = "steep.garbageCollector.enabled"

  /**
   * A unix-like cron expression specifying the interval at which the garbage
   * collector should be executed. See [org.quartz.CronExpression] for more
   * information about the format.
   */
  const val GARBAGECOLLECTOR_CRON = "steep.garbageCollector.cron"

  /**
   * The maximum time a [model.Submission] should be kept in the registry after
   * it has finished (regardless of whether it was successful or not). The
   * time can be specified as a human-readable duration (see [helper.toDuration]).
   */
  const val GARBAGECOLLECTOR_RETENTION_SUBMISSIONS = "steep.garbageCollector.retention.submissions"

  /**
   * The maximum time a [model.cloud.VM] should be kept in the registry after
   * it has been destroyed (regardless of its status). The time can be specified
   * as a human-readable duration (see [helper.toDuration]).
   */
  const val GARBAGECOLLECTOR_RETENTION_VMS = "steep.garbageCollector.retention.vms"

  /**
   * `true` if Steep should cache compiled plugin scripts on disk so they do
   * not have to be compiled again when Steep starts the next time.
   */
  const val CACHE_PLUGINS_ENABLED = "steep.cache.plugins.enabled"

  /**
   * A directory where Steep will keep cached compiled plugin scripts. The
   * directory will be created if it does not exist.
   */
  const val CACHE_PLUGINS_PATH = "steep.cache.plugins.path"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
