package cloud

import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_LEFT
import AddressConstants.REMOTE_AGENT_MISSING
import ConfigConstants
import ConfigConstants.CLOUD_AGENTPOOL
import ConfigConstants.CLOUD_CREATED_BY_TAG
import ConfigConstants.CLOUD_SETUPS_FILE
import ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION
import ConfigConstants.CLOUD_SSH_USERNAME
import agent.AgentRegistry
import agent.AgentRegistryFactory
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import com.mitchellbosecke.pebble.PebbleEngine
import db.VMRegistry
import db.VMRegistryFactory
import helper.JsonUtils
import helper.YamlUtils
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.shareddata.getLockAwait
import io.vertx.kotlin.core.shareddata.getLockWithTimeoutAwait
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.cloud.PoolAgentParams
import model.cloud.VM
import model.setup.Setup
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/**
 * Acquires remote agents on demand. Creates virtual machines, deploys Steep
 * to them, and destroys them if they are not needed anymore.
 * @author Michel Kraemer
 */
class CloudManager : CoroutineVerticle() {
  companion object {
    private val log = LoggerFactory.getLogger(CloudManager::class.java)

    /**
     * A metadata key indicating that a virtual machine has been created
     * by Steep
     */
    private const val CREATED_BY = "Created-By"

    /**
     * A metadata key indicating which setup a virtual machine has
     */
    private const val SETUP_ID = "Setup-Id"

    /**
     * A metadata key indicating the external ID of a VM to which a block
     * device has been attached (or was attached)
     */
    private const val VM_EXTERNAL_ID = "VM-External-Id"

    /**
     * Name of a cluster-wide lock used to make atomic operations on the
     * VM registry
     */
    private const val LOCK_VMS = "CloudManager.VMs.Lock"

    /**
     * A prefix for a lock that will be set while a VM is being created and
     * provisioned. The lock will be set before the creation starts and will
     * be released as soon as the agent has been successfully deployed.
     */
    private const val VM_CREATION_LOCK_PREFIX = "CloudManager.VMs.CreationLock."

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
   * by Steep
   */
  private lateinit var createdByTag: String

  /**
   * The username for SSH access to created virtual machines
   */
  private var sshUsername: String? = null

  /**
   * A SSH private key used for authentication when logging in to the new
   * virtual machines
   */
  private lateinit var sshPrivateKeyLocation: String

  /**
   * Parameters of remote agents the CloudManager maintains in its pool
   */
  private lateinit var poolAgentParams: List<PoolAgentParams>

  /**
   * A list of pre-configured setups
   */
  private lateinit var setups: List<Setup>

  /**
   * Registry to save created VMs
   */
  private lateinit var vmRegistry: VMRegistry

  /**
   * Agent registry
   */
  private lateinit var agentRegistry: AgentRegistry

  /**
   * Returns a list of setups that we can use to create VMs
   */
  private lateinit var setupSelector: SetupSelector

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

    sshUsername = config.getString(CLOUD_SSH_USERNAME)
    sshPrivateKeyLocation = config.getString(CLOUD_SSH_PRIVATE_KEY_LOCATION) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SSH_PRIVATE_KEY_LOCATION'")
    poolAgentParams = JsonUtils.mapper.convertValue(
        config.getJsonArray(CLOUD_AGENTPOOL, JsonArray()))

    // load setups file
    val setupsFile = config.getString(CLOUD_SETUPS_FILE) ?: throw IllegalStateException(
        "Missing configuration item `$CLOUD_SETUPS_FILE'")
    setups = YamlUtils.mapper.readValue(File(setupsFile))

    // if sshUsername is null, check if all setups have an sshUsername
    if (sshUsername == null) {
      for (setup in setups) {
        if (setup.sshUsername == null) {
          throw IllegalArgumentException("The configuration item " +
              "`$CLOUD_SSH_USERNAME' has not been set and setup " +
              "`${setup.id}' also does not have an SSH username.")
        }
      }
    }

    // check setups for duplicate IDs
    val setupIds = mutableSetOf<String>()
    for (setup in setups) {
      if (setupIds.contains(setup.id)) {
        throw IllegalArgumentException("Found duplicate setup ID: `${setup.id}'")
      }
      setupIds.add(setup.id)
    }

    // initialize registries
    vmRegistry = VMRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)

    // create setup selector
    setupSelector = SetupSelector(vmRegistry, poolAgentParams)

    // keep track of left agents
    vertx.eventBus().consumer<String>(REMOTE_AGENT_LEFT) { msg ->
      val agentId = msg.body().substring(REMOTE_AGENT_ADDRESS_PREFIX.length)
      log.info("Agent $agentId has left the cluster. Scheduling deletion of its VM ...")
      launch {
        vmRegistry.setVMStatus(agentId, VM.Status.RUNNING, VM.Status.LEFT)
      }
    }
    vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
      // reset the VM status if the agent has returned -- in the hope that the
      // VM has not been deleted by `sync()` in the meantime
      val agentId = msg.body().substring(REMOTE_AGENT_ADDRESS_PREFIX.length)
      log.info("Agent $agentId has joined the cluster.")
      launch {
        vmRegistry.setVMStatus(agentId, VM.Status.LEFT, VM.Status.RUNNING)
      }
    }

    syncTimerStart(true)
    sendKeepAliveTimerStart()

    // create new virtual machines on demand
    vertx.eventBus().consumer<JsonObject>(REMOTE_AGENT_MISSING) { msg ->
      val body = msg.body()
      val n = body.getLong("n", 1L)
      val requiredCapabilities = body.getJsonArray("requiredCapabilities")
      if (requiredCapabilities != null) {
        launch {
          createRemoteAgent(n, requiredCapabilities.map { it as String }.toSet())
        }
      }
    }
  }

  /**
   * Sync now and then regularly
   */
  private suspend fun syncTimerStart(cleanupOnly: Boolean = false) {
    try {
      sync(cleanupOnly)
    } catch (t: Throwable) {
      log.error("Could not sync state with Cloud", t)
    }
    syncTimer()
  }

  /**
   * Start a periodic timer that synchronizes the VM registry with the Cloud
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
   * Tries to create a lock for the VM with the given [id]. As long as the lock
   * is held, the VM is being created and provisioned. The method returns `null`
   * if the lock could not be acquired.
   */
  private suspend fun tryLockVM(id: String): Lock? {
    val lockName = VM_CREATION_LOCK_PREFIX + id
    return try {
      vertx.sharedData().getLockWithTimeoutAwait(lockName, 1)
    } catch (t: Throwable) {
      // Could not acquire lock. Assume someone else is already creating the VM
      null
    }
  }

  /**
   * Synchronize the VM registry with the Cloud
   */
  private suspend fun sync(cleanupOnly: Boolean = false) {
    log.trace("Syncing VMs ...")

    // destroy all virtual machines whose agents have left
    val vmsToRemove = vmRegistry.findVMs(VM.Status.LEFT)
    for (vm in vmsToRemove) {
      log.info("Destroying VM of left agent `${vm.id}' ...")
      vmRegistry.forceSetVMStatus(vm.id, VM.Status.DESTROYING)
      if (vm.externalId != null) {
        cloudClient.destroyVM(vm.externalId)
      }
      vmRegistry.forceSetVMStatus(vm.id, VM.Status.DESTROYED)
      vmRegistry.setVMReason(vm.id, "Agent has left the cluster")
      vmRegistry.setVMDestructionTime(vm.id, Instant.now())
    }

    // destroy orphaned VMs:
    // - VMs that we created before but that are not in our registry
    // - VMs that we created but that do not have an agent and won't get one
    val existingVMs = cloudClient.listVMs { createdByTag == it[CREATED_BY] }
    val deleteDeferreds = mutableListOf<Deferred<String>>()
    for (externalId in existingVMs) {
      val id = vmRegistry.findVMByExternalId(externalId)?.id
      val shouldDelete = if (id == null) {
        // we don't know this VM
        true
      } else {
        val lock = tryLockVM(id)
        if (lock == null) {
          // someone else is currently creating the VM
          false
        } else {
          lock.release()

          // No one is currently creating the VM. Delete it if there is no
          // corresponding agent.
          !agentRegistry.getAgentIds().contains(id)
        }
      }

      if (shouldDelete) {
        val active = try {
          cloudClient.isVMActive(externalId)
        } catch (e: NoSuchElementException) {
          false
        }
        if (active) {
          deleteDeferreds.add(async {
            log.info("Found orphaned VM `$externalId' ...")
            cloudClient.destroyVM(externalId)
            if (id != null) {
              vmRegistry.forceSetVMStatus(id, VM.Status.DESTROYED)
              vmRegistry.setVMReason(id, "VM was orphaned")
              vmRegistry.setVMDestructionTime(id, Instant.now())
            }
            externalId
          })
        }
      }
    }
    val deletedVMs = deleteDeferreds.awaitAll()
    val remainingVMs = existingVMs.toSet() - deletedVMs

    // update status of VMs that don't exist anymore
    val nonTerminatedVMs = vmRegistry.findNonTerminatedVMs()
    for (nonTerminatedVM in nonTerminatedVMs) {
      val lock = tryLockVM(nonTerminatedVM.id)
      val shouldUpdateStatus = if (lock == null) {
        // someone is currently creating the VM
        false
      } else {
        // no one is currently creating the VM
        lock.release()

        if (nonTerminatedVM.externalId != null) {
          // Entry has an external ID. Check if there is a corresponding VM.
          !existingVMs.contains(nonTerminatedVM.externalId)
        } else {
          // Entry has no external ID. It's an orphan.
          true
        }
      }

      if (shouldUpdateStatus) {
        log.info("Setting status of deleted VM `${nonTerminatedVM.id}' to DESTROYED")
        vmRegistry.forceSetVMStatus(nonTerminatedVM.id, VM.Status.DESTROYED)
        vmRegistry.setVMReason(nonTerminatedVM.id, "VM did not exist anymore")
      }
    }

    // delete block devices that are not attached to a VM (anymore) and whose
    // external VM ID is unknown
    val unattachedBlockDevices = cloudClient.listAvailableBlockDevices { bd ->
      createdByTag == bd[CREATED_BY] && (!bd.containsKey(VM_EXTERNAL_ID) ||
          !remainingVMs.contains(bd[VM_EXTERNAL_ID]))
    }
    unattachedBlockDevices.map { volumeId ->
      async {
        log.info("Deleting unattached volume `$volumeId' ...")
        cloudClient.destroyBlockDevice(volumeId)
      }
    }.awaitAll()

    if (!cleanupOnly) {
      // ensure there's a minimum number of VMs
      launch {
        createRemoteAgent { setupSelector.selectMinimum(setups) }
      }
    }
  }

  /**
   * Send keep-alive messages now and then regularly
   */
  private suspend fun sendKeepAliveTimerStart() {
    try {
      sendKeepAlive()
    } catch (t: Throwable) {
      log.error("Could not send keep-alive messages", t)
    }
    sendKeepAliveTimer()
  }

  /**
   * Send keep-alive messages to a minimum of remote agents (so that they
   * do not shut down themselves). See [model.setup.Setup.minVMs] and
   * [PoolAgentParams.min]
   */
  private suspend fun sendKeepAlive() {
    // get existing VMs
    val registeredVmsPerSetup = vmRegistry.findNonTerminatedVMs().groupBy { it.setup.id }

    // sort entries so they have the same order as the configured setups
    // this is necessary so we get the same results as [SetupSelector.selectMinimum]
    val vmsPerSetup = mutableMapOf<String, List<VM>>()
    for (setup in setups) {
      registeredVmsPerSetup[setup.id]?.let {
        vmsPerSetup.put(setup.id, it)
      }
    }

    val setupsById = setups.map { it.id to it }.toMap()
    val pap = poolAgentParams.toMutableList()
    for ((setupId, vms) in vmsPerSetup) {
      val setup = setupsById[setupId] ?: continue

      // get minimum number of VMs defined by setup
      var minimum = min(vms.size, setup.minVMs)

      // check if there are pool agent parameters that also require a minimum
      // number of VMs
      val papToAdd = mutableListOf<PoolAgentParams>()
      val papi = pap.iterator()
      while (papi.hasNext()) {
        val p = papi.next()
        if (setup.providedCapabilities.containsAll(p.capabilities)) {
          // we found parameters our setup satisfies
          if (p.min > minimum) {
            minimum = min(vms.size, p.min)
          }
          papi.remove()
          if (p.min - minimum > 0) {
            papToAdd.add(p.copy(min = p.min - minimum))
          }
        }
      }
      pap.addAll(papToAdd)

      if (minimum > 0) {
        // Get a minimum number of VMs. Sort the VMs by ID and take the first
        // `minimum` ones. This makes sure we always send messages to the same VMs.
        val minVMs = vms.sortedBy { it.id }.take(minimum)

        // send keep-alive message to these VMs
        for (vm in minVMs) {
          val address = REMOTE_AGENT_ADDRESS_PREFIX + vm.id
          val msg = json {
            obj(
                "action" to "keepAlive"
            )
          }
          vertx.eventBus().send(address, msg)
        }
      }
    }
  }

  /**
   * Start a periodic timer that sends keep-alive messages to remote agents
   */
  private fun sendKeepAliveTimer() {
    val seconds = config.getLong(ConfigConstants.CLOUD_KEEP_ALIVE_INTERVAL, 30L)
    vertx.setTimer(1000 * seconds) {
      launch {
        sendKeepAliveTimerStart()
      }
    }
  }

  /**
   * Create up to [n] virtual machines with the given [requiredCapabilities]
   * and deploy a remote agent to each of them
   */
  internal suspend fun createRemoteAgent(n: Long, requiredCapabilities: Collection<String>) {
    createRemoteAgent { setupSelector.select(n, requiredCapabilities, setups) }
  }

  private suspend fun createRemoteAgent(selector: suspend () -> List<Setup>) {
    // atomically create VM entries in the registry
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_VMS)
    val vmsToCreate = try {
      val setupsToCreate = selector()
      setupsToCreate.map { setup ->
        VM(setup = setup).also {
          vmRegistry.addVM(it)
        } to setup
      }
    } finally {
      lock.release()
    }
    createRemoteAgents(vmsToCreate)
  }

  /**
   * Create virtual machines and deploy remote agents to them based on the
   * given list of registered [vmsToCreate] and their corresponding setups
   */
  private suspend fun createRemoteAgents(vmsToCreate: List<Pair<VM, Setup>>) {
    val sharedData = vertx.sharedData()
    val deferreds = vmsToCreate.map { (vm, setup) ->
      // create multiple VMs in parallel
      async {
        // hold a lock as long as we are creating this VM
        val creatingLock = sharedData.getLockAwait(VM_CREATION_LOCK_PREFIX + vm.id)
        try {
          log.info("Creating virtual machine ${vm.id} with setup `${setup.id}' ...")

          if (backoffSeconds > 10) {
            log.info("Backing off for $backoffSeconds seconds due to too many failed attempts.")
            delay(backoffSeconds * 1000L)
          }

          try {
            // create VM
            val externalId = createVM(vm.id, setup)
            vmRegistry.setVMExternalID(vm.id, externalId)
            vmRegistry.setVMCreationTime(vm.id, Instant.now())

            // create other volumes in background
            val volumeDeferreds = createVolumesAsync(externalId, setup)

            try {
              cloudClient.waitForVM(externalId)

              val volumeIds = volumeDeferreds.awaitAll()
              for (volumeId in volumeIds) {
                cloudClient.attachVolume(externalId, volumeId)
              }

              val ipAddress = cloudClient.getIPAddress(externalId)
              vmRegistry.setVMIPAddress(vm.id, ipAddress)

              vmRegistry.setVMStatus(vm.id, VM.Status.CREATING, VM.Status.PROVISIONING)
              provisionVM(ipAddress, vm.id, externalId, setup)
            } catch (e: Throwable) {
              vmRegistry.forceSetVMStatus(vm.id, VM.Status.DESTROYING)
              cloudClient.destroyVM(externalId)
              for (vd in volumeDeferreds) {
                val volumeId = try {
                  vd.await()
                } catch (vt: Throwable) {
                  log.error("Could not create volume", vt)
                  null
                }
                volumeId?.let { cloudClient.destroyBlockDevice(it) }
              }
              throw e
            }

            vmRegistry.setVMStatus(vm.id, VM.Status.PROVISIONING, VM.Status.RUNNING)
            vmRegistry.setVMAgentJoinTime(vm.id, Instant.now())
            backoffSeconds = 0
          } catch (t: Throwable) {
            vmRegistry.forceSetVMStatus(vm.id, VM.Status.ERROR)
            vmRegistry.setVMReason(vm.id, t.message ?: "Unknown error")
            vmRegistry.setVMDestructionTime(vm.id, Instant.now())
            backoffSeconds = min(MAX_BACKOFF_SECONDS, max(backoffSeconds * 2, 2))
            throw t
          }
        } finally {
          creatingLock.release()
        }
      }
    }

    deferreds.awaitAll()
  }

  /**
   * Create a virtual machine with the given internal [id] and [Setup] and
   * return its external ID
   */
  private suspend fun createVM(id: String, setup: Setup): String {
    val metadata = mapOf(CREATED_BY to createdByTag, SETUP_ID to setup.id)

    val name = "fraunhofer-steep-${id}"
    val imageId = cloudClient.getImageID(setup.imageName)
    val blockDeviceId = cloudClient.createBlockDevice(setup.blockDeviceSizeGb,
        setup.blockDeviceVolumeType, imageId, true, setup.availabilityZone, metadata)
    try {
      return cloudClient.createVM(name, setup.flavor, blockDeviceId,
          setup.availabilityZone, metadata)
    } catch (t: Throwable) {
      cloudClient.destroyBlockDevice(blockDeviceId)
      throw t
    }
  }

  /**
   * Asynchronously create all additional volumes for the VM with the given
   * [externalId] specified by the given [setup]. Return a list of [Deferred]
   * objects that can be used to wait for the completion of the asynchronous
   * operation and to obtain the IDs of the created volumes.
   */
  private suspend fun createVolumesAsync(externalId: String, setup: Setup): List<Deferred<String>> {
    val metadata = mapOf(CREATED_BY to createdByTag, SETUP_ID to setup.id,
        VM_EXTERNAL_ID to externalId)
    return setup.additionalVolumes.map { volume ->
      async {
        cloudClient.createBlockDevice(volume.sizeGb, volume.type, null, false,
            volume.availabilityZone ?: setup.availabilityZone, metadata)
      }
    }
  }

  /**
   * Provision a virtual machine
   * @param ipAddress the VM's IP address
   * @param vmId the VM's ID
   * @param externalId the VM's external ID
   * @param setup the setup that contains information how to provision the VM
   */
  private suspend fun provisionVM(ipAddress: String, vmId: String,
      externalId: String, setup: Setup) {
    val ssh = SSHClient(ipAddress, setup.sshUsername ?: sshUsername!!,
        sshPrivateKeyLocation, vertx)
    waitForSSH(ipAddress, externalId, ssh)

    // register a handler that waits for the agent on the new virtual machine
    // to become available
    val promise = Promise.promise<Unit>()
    val consumer = vertx.eventBus().consumer<String>(REMOTE_AGENT_ADDED) { msg ->
      if (msg.body() == REMOTE_AGENT_ADDRESS_PREFIX + vmId) {
        promise.complete()
      }
    }

    log.info("Provisioning server $ipAddress ...")

    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .newLineTrimming(false)
        .extension(ProvisionTemplateExtension())
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
      promise.fail("Remote agent `$vmId' with IP address `$ipAddress' did " +
          "not become available after $timeout ms")
    }

    try {
      promise.future().await()
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
   * @param externalId the external ID of the virtual machine to wait for
   * @param ssh an SSH client
   */
  private suspend fun waitForSSH(ipAddress: String, externalId: String, ssh: SSHClient) {
    val retries = 150
    val retrySeconds = 2

    for (i in 1..retries) {
      cloudClient.waitForVM(externalId)

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
