package cloud

import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_LEFT
import AddressConstants.REMOTE_AGENT_MISSING
import ConfigConstants.CLOUD_CREATED_BY_TAG
import ConfigConstants.CLOUD_SETUPS_FILE
import ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION
import ConfigConstants.CLOUD_SSH_USERNAME
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import helper.YamlUtils
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.shareddata.compareAndSetAwait
import io.vertx.kotlin.core.shareddata.getAsyncMapAwait
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.getCounterAwait
import io.vertx.kotlin.core.shareddata.getLockWithTimeoutAwait
import io.vertx.kotlin.core.shareddata.incrementAndGetAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.removeAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.setup.Setup
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.StringWriter
import kotlin.math.max
import kotlin.math.min

/**
 * Acquires remote agents on demand. Creates virtual machines, deploys the
 * JobManager to them, and destroys them if they are not needed anymore.
 * @author Michel Kraemer
 */
class CloudManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(CloudManager::class.java)

    /**
     * A metadata key indicating that a virtual machine has been created
     * by the JobManager
     */
    private const val CREATED_BY = "Created-By"

    /**
     * A metadata key indicating which setup a virtual machine has
     */
    private const val SETUP_ID = "Setup-Id"

    /**
     * The name of a map containing IDs of created virtual machines
     */
    private const val CREATED_VMS_MAP_NAME = "CloudManager.CreatedVMs"

    /**
     * Prefix for counters that keep track of how many VMs we created
     * with a certain setup
     */
    private const val COUNTER_PREFIX = "CloudManager.Counter."

    /**
     * Prefix for locks that keep track of which setups we are currently creating
     */
    private const val CREATING_SETUPS_PREFIX = "CloudManager.CreatingSetups."

    /**
     * The maximum number of seconds to backoff between failed attempts to
     * create a VM
     */
    private const val MAX_BACKOFF_SECONDS = 60 * 60
  }

  /**
   * The client to connect to the Cloud
   */
  private lateinit var cloudClient: CloudClient

  /**
   * A metadata item indicating that a virtual machine has been created
   * by the JobManager
   */
  private lateinit var createdByTag: String

  /**
   * The username for SSH access to created virtual machines
   */
  private lateinit var sshUsername: String

  /**
   * A SSH private key used for authentication when logging in to the new
   * virtual machines
   */
  private lateinit var sshPrivateKeyLocation: String

  /**
   * A list of pre-configured setups
   */
  private lateinit var setups: List<Setup>

  /**
   * A map containing IDs of created virtual machines and the respective ID of
   * their setup
   */
  private lateinit var createdVMs: AsyncMap<String, String>

  /**
   * A set of IDs of agents that have recently left the cluster
   */
  private val leftAgents = mutableSetOf<String>()

  /**
   * The current number of seconds to wait before the next attempt to create a VM
   */
  private var backoffSeconds = 0

  override suspend fun start() {
    log.info("Launching cloud manager ...")

    // load configuration
    cloudClient = CloudClientFactory.create(vertx)
    createdByTag = config.getString(CLOUD_CREATED_BY_TAG) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_CREATED_BY_TAG'")

    sshUsername = config.getString(CLOUD_SSH_USERNAME) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_USERNAME'")
    sshPrivateKeyLocation = config.getString(CLOUD_SSH_PRIVATE_KEY_LOCATION) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_PRIVATE_KEY_LOCATION'")

    // load setups file
    val setupsFile = config.getString(CLOUD_SETUPS_FILE) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SETUPS_FILE'")
    setups = YamlUtils.mapper.readValue(File(setupsFile))

    // initialize shared maps
    val sharedData = vertx.sharedData()
    createdVMs = sharedData.getAsyncMapAwait(CREATED_VMS_MAP_NAME)

    // keep track of left agents
    vertx.eventBus().consumer<String>(REMOTE_AGENT_LEFT) { msg ->
      val agentId = msg.body().substring(REMOTE_AGENT_ADDRESS_PREFIX.length)
      log.info("Agent $agentId has left the cluster. Scheduling deletion of its VM ...")
      leftAgents.add(agentId)
    }
    vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
      // remove the `agentId` from `leftAgents` if the agent has returned -- in
      // the hope that the VM has not been deleted by `sync()` in the meantime
      val agentId = msg.body().substring(REMOTE_AGENT_ADDRESS_PREFIX.length)
      log.info("Agent $agentId has joined the cluster.")
      leftAgents.remove(agentId)
    }

    syncTimerStart()

    // create new virtual machines on demand
    vertx.eventBus().consumer<JsonArray>(REMOTE_AGENT_MISSING) { msg ->
      val requiredCapabilities = msg.body()
      if (requiredCapabilities != null) {
        launch {
          createRemoteAgent(requiredCapabilities.map { it as String }.toSet())
        }
      }
    }
  }

  /**
   * Sync now and then regularly
   */
  private suspend fun syncTimerStart() {
    try {
      sync()
    } catch (t: Throwable) {
      log.error("Could not sync state with Cloud", t)
    }
    syncTimer()
  }

  /**
   * Start a periodic timer that synchronizes our shared maps with the Cloud
   */
  private fun syncTimer() {
    val seconds = config.getLong(ConfigConstants.CLOUD_SYNC_INTERVAL, 120L)
    vertx.setTimer(1000 * seconds) {
      launch {
        syncTimerStart()
      }
    }
  }

  /**
   * Synchronize our shared maps with the Cloud
   */
  private suspend fun sync() {
    log.debug("Syncing VMs ...")

    // destroy all virtual machines whose agents have left
    val vmIDsToRemove = leftAgents.toList()
    for (id in vmIDsToRemove) {
      log.info("Destroying VM of left agent `$id' ...")
      cloudClient.destroyVM(id)
      leftAgents.remove(id)
    }

    // destroy all virtual machines that we created before but don't know anymore
    val existingVMs = cloudClient.listVMs { createdByTag == it[CREATED_BY] }
    for (id in existingVMs) {
      if (createdVMs.getAwait(id) == null) {
        val active = try {
          cloudClient.isVMActive(id)
        } catch (e: NoSuchElementException) {
          false
        }
        if (active) {
          log.info("Found orphaned VM `$id' ...")
          cloudClient.destroyVM(id)
        }
      }
    }

    // remove virtual machines from our map if they don't exist anymore
    val entries = awaitResult<Map<String, String>> { createdVMs.entries(it) }
    for ((vmId, setupId) in entries) {
      if (!existingVMs.contains(vmId)) {
        log.info("Removing deleted VM `$vmId' from internal data structures.")

        // remove from map of created VMs
        createdVMs.removeAwait(vmId)

        // decrement counter for setup but never get below 0
        val counter = vertx.sharedData().getCounterAwait(COUNTER_PREFIX + setupId)
        var v = counter.getAwait()
        var success = false
        while (v > 0L && !success) {
          success = counter.compareAndSetAwait(v, v - 1)
          if (!success) {
            v = counter.getAwait()
          }
        }
      }
    }
  }

  /**
   * Select a [Setup] that satisfies the given [requiredCapabilities]. May
   * return `null` if there is no matching [Setup].
   */
  private fun selectSetup(requiredCapabilities: Set<String>): Setup? =
      setups.find { it.providedCapabilities.containsAll(requiredCapabilities) }

  /**
   * Create a virtual machine that matches the given [requiredCapabilities]
   * and deploy a remote agent to it
   */
  suspend fun createRemoteAgent(requiredCapabilities: Set<String>) {
    val setup = selectSetup(requiredCapabilities) ?: throw IllegalStateException(
        "Could not find a setup that can satisfy the required capabilities: " +
            requiredCapabilities)

    // get number of existing VMs with this setup
    val counter = vertx.sharedData().getCounterAwait(COUNTER_PREFIX + setup.id)
    val nBefore = counter.getAwait()

    // check if we're already creating a VM with this setup
    val lockName = CREATING_SETUPS_PREFIX + setup.id + ".0"
    val lock = try {
      vertx.sharedData().getLockWithTimeoutAwait(lockName, 1)
    } catch (t: Throwable) {
      // Could not acquire lock. Assume someone else is already creating a VM
      // with this setup.
      return
    }

    try {
      val nAfter = counter.getAwait()
      if (nAfter > nBefore) {
        // we just finished creating a VM with this setup while we tried to
        // acquire the lock
        return
      }
      if (nAfter >= setup.maxVMs.toLong()) {
        // we already created more than enough virtual machines with this setup
        return
      }

      log.info("Creating virtual machine with setup `${setup.id}' for " +
          "capabilities $requiredCapabilities ...")

      if (backoffSeconds > 10) {
        log.info("Backing off for $backoffSeconds seconds due to too many failed attempts.")
        delay(backoffSeconds * 1000L)
      }

      try {
        val vmId = createVM(setup)
        try {
          createdVMs.putAwait(vmId, setup.id)
          cloudClient.waitForVM(vmId)
          val ipAddress = cloudClient.getIPAddress(vmId)
          provisionVM(ipAddress, vmId, setup)
          counter.incrementAndGetAwait()
        } catch (e: Throwable) {
          createdVMs.removeAwait(vmId)
          cloudClient.destroyVM(vmId)
          throw e
        }
        backoffSeconds = 0
      } catch (t: Throwable) {
        backoffSeconds = min(MAX_BACKOFF_SECONDS, max(backoffSeconds * 2, 2))
        throw t
      }
    } finally {
      // release lock that says we're currently creating a VM with this setup
      lock.release()
    }
  }

  /**
   * Create a virtual machine with the given [Setup] and return its ID
   */
  private suspend fun createVM(setup: Setup): String {
    val metadata = mapOf(CREATED_BY to createdByTag, SETUP_ID to setup.id)

    val imageId = cloudClient.getImageID(setup.imageName)
    val blockDeviceId = cloudClient.createBlockDevice(imageId,
        setup.blockDeviceSizeGb, setup.blockDeviceVolumeType, metadata)
    try {
      return cloudClient.createVM(setup.flavor, blockDeviceId, metadata)
    } catch (t: Throwable) {
      cloudClient.destroyBlockDevice(blockDeviceId)
      throw t
    }
  }

  /**
   * Provisions a virtual machine
   * @param ipAddress the VM's IP address
   * @param vmId the VM's ID
   * @param setup the setup that contains information how to provision the VM
   */
  private suspend fun provisionVM(ipAddress: String, vmId: String, setup: Setup) {
    val ssh = SSHClient(ipAddress, sshUsername, sshPrivateKeyLocation, vertx)
    waitForSSH(ipAddress, vmId, ssh)

    // register a handler that waits for the agent on the new virtual machine
    // to become available
    val future = Future.future<Unit>()
    val consumer = vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
      if (msg.body() == REMOTE_AGENT_ADDRESS_PREFIX + vmId) {
        future.complete()
      }
    }

    log.info("Provisioning server $ipAddress ...")

    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .newLineTrimming(false)
        .build()
    val context = mapOf<String, Any>(
        "config" to config.map,
        "env" to System.getenv(),
        "ipAddress" to ipAddress,
        "agentId" to vmId,
        "agentCapabilities" to setup.providedCapabilities
    )

    // run provisioning scripts
    for (script in setup.provisioningScripts) {
      // compile script template
      val compiledTemplate = engine.getTemplate(script)
      val writer = StringWriter()
      compiledTemplate.evaluate(writer, context)

      // upload compiled script
      val destFileName = "/tmp/" + FilenameUtils.getName(script)
      val tmpFile = File.createTempFile("job", null)
      tmpFile.deleteOnExit()
      try {
        tmpFile.writeText(writer.toString())
        ssh.uploadFile(tmpFile.absolutePath, destFileName)
      } finally {
        tmpFile.delete()
      }

      // execute script
      ssh.execute("sudo chmod +x $destFileName")
      ssh.execute("sudo $destFileName")
    }

    // throw if the agent does not become available after a set amount of time
    // TODO make time configurable
    val timeout = 1000 * 60 * 5L
    val timerId = vertx.setTimer(timeout) {
      future.fail("Remote agent `$vmId' with IP address `$ipAddress' did " +
          "not become available after $timeout ms")
    }

    try {
      future.await()
      log.info("Successfully created remote agent `$vmId' with IP " +
          "address `$ipAddress'.")
    } finally {
      consumer.unregister()
      vertx.cancelTimer(timerId)
    }
  }

  /**
   * Wait for an SSH connection to become available
   * @param ipAddress the IP address of the virtual machine to wait for
   * @param vmId the ID of the virtual machine to wait for
   * @param ssh an SSH client
   */
  private suspend fun waitForSSH(ipAddress: String, vmId: String, ssh: SSHClient) {
    val retries = 150
    val retrySeconds = 2

    for (i in 1..retries) {
      cloudClient.waitForVM(vmId)

      log.info("Waiting for SSH: $ipAddress")

      try {
        ssh.tryConnect(retrySeconds)
      } catch (e: IOException) {
        delay(retrySeconds * 1000L)
        continue
      }

      return
    }

    throw IllegalStateException("Too many attempts to connect to SSH")
  }
}
