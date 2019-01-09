package cloud

import AddressConstants
import ConfigConstants.CLOUD_CREATED_BY_TAG
import ConfigConstants.CLOUD_SETUPS_FILE
import ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION
import ConfigConstants.CLOUD_SSH_USERNAME
import agent.RemoteAgentMetadata
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import helper.JsonUtils
import helper.UniqueID
import helper.YamlUtils
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.setup.Setup
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.StringWriter

/**
 * Acquires remote agents on demand. Creates virtual machines, deploys the
 * JobManager to them, and destroys them if they are not needed anymore.
 * @author Michel Kraemer
 */
class CloudManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(CloudManager::class.java)
    private const val CREATED_BY = "Created-By"
    private const val SETUP_ID = "Setup-Id"
  }

  private lateinit var cloudClient: CloudClient
  private lateinit var createdByTag: String
  private lateinit var sshUsername: String
  private lateinit var sshPrivateKeyLocation: String
  private lateinit var setups: List<Setup>

  override suspend fun start() {
    log.info("Launching cloud manager ...")

    cloudClient = CloudClientFactory.create(vertx)
    createdByTag = config.getString(CLOUD_CREATED_BY_TAG) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_CREATED_BY_TAG'")

    sshUsername = config.getString(CLOUD_SSH_USERNAME) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_USERNAME'")
    sshPrivateKeyLocation = config.getString(CLOUD_SSH_PRIVATE_KEY_LOCATION) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_PRIVATE_KEY_LOCATION'")

    val setupsFile = config.getString(CLOUD_SETUPS_FILE) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SETUPS_FILE'")
    setups = YamlUtils.mapper.readValue(File(setupsFile))

    // TODO limit to maximum number of VMs per setup
//    if (cloudClient.listVMs { createdByTag == it[CREATED_BY] }.size == setup.maxVMs) {
//      // there are already enough VMs
//      return
//    }

    // create new virtual machines on demand
    var creating = false
    vertx.eventBus().consumer<JsonArray>(AddressConstants.REMOTE_AGENT_MISSING) { msg ->
      if (!creating) {
        creating = true

        val requiredCapabilities = msg.body().map { it as String }.toSet()
        launch {
          try {
            createRemoteAgent(requiredCapabilities)
          } finally {
            creating = false
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
  private suspend fun createRemoteAgent(requiredCapabilities: Set<String>) {
    val setup = selectSetup(requiredCapabilities) ?: throw IllegalStateException(
        "Could not find a setup that can satisfy the required capabilities: " +
            requiredCapabilities)

    log.info("Creating virtual machine with setup `${setup.id}' for " +
        "capabilities $requiredCapabilities ...")

    val vmId = createVM(setup)
    try {
      val ipAddress = cloudClient.getIPAddress(vmId)
      val agentId = UniqueID.next()
      provisionVM(ipAddress, vmId, agentId, setup)
    } catch (e: Throwable) {
      cloudClient.destroyVM(vmId)
      throw e
    }
  }

  /**
   * Create a virtual machine with the given [Setup] and return its ID
   */
  private suspend fun createVM(setup: Setup): String {
    val metadata = mapOf(CREATED_BY to createdByTag, SETUP_ID to setup.id)

    val imageId = cloudClient.getImageID(setup.imageName)
    val blockDeviceId = cloudClient.createBlockDevice(imageId,
        setup.blockDeviceSizeGb, metadata)
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
   * @param agentId the ID the agent running on the new VM should have
   * @param setup the setup that contains information how to provision the VM
   */
  private suspend fun provisionVM(ipAddress: String, vmId: String,
      agentId: String, setup: Setup) {
    val ssh = SSHClient(ipAddress, sshUsername, sshPrivateKeyLocation, vertx)
    waitForSSH(ipAddress, vmId, ssh)

    log.info("Provisioning server $ipAddress ...")

    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .build()
    val context = mapOf<String, Any>(
        "config" to config.map,
        "ipAddress" to ipAddress,
        "agentId" to agentId,
        "agentCapabilities" to setup.providedCapabilities
    )

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

    // wait for the new virtual machine to join the cluster
    val future = Future.future<Unit>()
    val consumer = vertx.eventBus().consumer<JsonObject>(AddressConstants.REMOTE_AGENT_ADDED) { msg ->
      val metadata = JsonUtils.fromJson<RemoteAgentMetadata>(msg.body())
      if (metadata.id == agentId) {
        future.complete()
      }
    }

    // throw if the agent does not become available after a set amount of time
    // TODO make time configurable
    val timeout = 1000 * 60 * 5L
    val timerId = vertx.setTimer(timeout) {
      future.fail("Remote agent `$agentId' on virtual machine `$vmId' with " +
          "IP address `$ipAddress' did not become available after $timeout ms")
    }

    try {
      future.await()
      log.info("Successfully created remote agent `$agentId' on virtual " +
          "machine `$vmId' with IP address `$ipAddress'.")
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
