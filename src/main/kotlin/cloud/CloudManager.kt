package cloud

import AddressConstants.CLUSTER_NODE_LEFT
import AddressConstants.REMOTE_AGENT_ADDED
import AddressConstants.REMOTE_AGENT_ADDRESS_PREFIX
import AddressConstants.REMOTE_AGENT_MISSING
import ConfigConstants
import ConfigConstants.CLOUD_AGENTPOOL
import ConfigConstants.CLOUD_CREATED_BY_TAG
import ConfigConstants.CLOUD_SSH_PRIVATE_KEY_LOCATION
import ConfigConstants.CLOUD_SSH_USERNAME
import agent.AgentRegistry
import agent.AgentRegistryFactory
import cloud.template.ProvisioningTemplateExtension
import com.fasterxml.jackson.module.kotlin.convertValue
import com.mitchellbosecke.pebble.PebbleEngine
import db.SetupRegistryFactory
import db.VMRegistry
import db.VMRegistryFactory
import helper.JsonUtils
import helper.toDuration
import io.vertx.core.Promise
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.shareddata.Lock
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.cloud.PoolAgentParams
import model.cloud.VM
import model.retry.RetryPolicy
import model.setup.Setup
import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.time.Duration
import java.time.Instant
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
     * Name of a cluster-wide lock used to run [sync] only once at the same time
     */
    private const val LOCK_SYNC = "CloudManager.Sync.Lock"

    /**
     * Name of a cluster-wide map to store circuit breaker states
     */
    private const val CLUSTER_MAP_CIRCUIT_BREAKERS = "CloudManager.Map.CircuitBreakers"
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
  internal lateinit var setups: List<Setup>

  /**
   * Default creation policy if none is defined in the [Setup]
   */
  private lateinit var defaultCreationPolicyRetries: RetryPolicy
  private var defaultCreationPolicyLockAfterRetries: Long = 0

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
   * Circuit breakers for all setups that specify the maximum number of attempts
   * to create a VM, delays, and whether another attempt can be performed or not
   */
  private lateinit var setupCircuitBreakers: VMCircuitBreakerMap

  /**
   * The maximum time the cloud manager should try to log in to a new VM via SSH
   */
  private var timeoutSshReady = Duration.ofMinutes(5)

  /**
   * The maximum time the cloud manager should wait for a new agent to become
   * available
   */
  private var timeoutAgentReady = Duration.ofMinutes(5)

  /**
   * The maximum time that creating a VM may take before it is aborted with an
   * error
   */
  private var timeoutCreateVM = Duration.ofMinutes(5)

  /**
   * The maximum time that destroying a VM may take before it is aborted with
   * an error
   */
  private var timeoutDestroyVM = Duration.ofMinutes(5)

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

    timeoutSshReady = config.getString(ConfigConstants.CLOUD_TIMEOUTS_SSHREADY)
        ?.toDuration() ?: timeoutSshReady
    timeoutAgentReady = config.getString(ConfigConstants.CLOUD_TIMEOUTS_AGENTREADY)
        ?.toDuration() ?: timeoutAgentReady
    timeoutCreateVM = config.getString(ConfigConstants.CLOUD_TIMEOUTS_CREATEVM)
        ?.toDuration() ?: timeoutCreateVM
    timeoutDestroyVM = config.getString(ConfigConstants.CLOUD_TIMEOUTS_DESTROYVM)
        ?.toDuration() ?: timeoutDestroyVM

    // load setups file
    setups = SetupRegistryFactory.create(vertx).findSetups()

    // load values of default creation policy
    // the default values here describe a time frame of 10 minutes in which
    // at most five attempts will be made to create a VM with a subsequent
    // lock time of 20 minutes.
    defaultCreationPolicyRetries = RetryPolicy(
        maxAttempts = config.getInteger(
            ConfigConstants.CLOUD_SETUPS_CREATION_RETRIES_MAXATTEMPTS, 5),
        delay = config.getString(
            ConfigConstants.CLOUD_SETUPS_CREATION_RETRIES_DELAY)
            ?.toDuration()?.toMillis() ?: 40_000L, // 40 seconds
        exponentialBackoff = config.getInteger(
            ConfigConstants.CLOUD_SETUPS_CREATION_RETRIES_EXPONENTIALBACKOFF, 2),
        maxDelay = config.getString(
            ConfigConstants.CLOUD_SETUPS_CREATION_RETRIES_MAXDELAY)
            ?.toDuration()?.toMillis()
    )
    defaultCreationPolicyLockAfterRetries = config.getString(
        ConfigConstants.CLOUD_SETUPS_CREATION_LOCKAFTERRETRIES)
        ?.toDuration()?.toMillis() ?: (20 * 60 * 1000L) // 20 minutes

    // initialize cluster map for circuit breakers
    setupCircuitBreakers = VMCircuitBreakerMap(CLUSTER_MAP_CIRCUIT_BREAKERS,
        setups, defaultCreationPolicyRetries, defaultCreationPolicyLockAfterRetries,
        vertx)

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

    // initialize registries
    vmRegistry = VMRegistryFactory.create(vertx)
    agentRegistry = AgentRegistryFactory.create(vertx)

    // create setup selector
    setupSelector = SetupSelector(vmRegistry, poolAgentParams)

    // keep track of left cluster nodes
    vertx.eventBus().consumer<JsonObject>(CLUSTER_NODE_LEFT) { msg ->
      val agentId = msg.body().getString("agentId")
      log.info("Cluster node `$agentId' has left the cluster. Scheduling deletion of its VM ...")
      launch {
        vmRegistry.setVMStatus(agentId, VM.Status.RUNNING, VM.Status.LEFT)
      }
    }
    vertx.eventBus().consumer(REMOTE_AGENT_ADDED) { msg ->
      // reset the VM status if the agent has returned -- in the hope that the
      // VM has not been deleted by `sync()` in the meantime
      val agentId = msg.body()
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
    val milliseconds = config.getString(ConfigConstants.CLOUD_SYNC_INTERVAL, "2m")
        .toDuration().toMillis()
    vertx.setTimer(milliseconds) {
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
      vertx.sharedData().getLockWithTimeout(lockName, 1).await()
    } catch (t: Throwable) {
      // Could not acquire lock. Assume someone else is already creating the VM
      null
    }
  }

  /**
   * Synchronize the VM registry with the Cloud
   */
  private suspend fun sync(cleanupOnly: Boolean = false) {
    val syncLock = try {
      vertx.sharedData().getLockWithTimeout(LOCK_SYNC, 5000).await()
    } catch (t: Throwable) {
      // Someone else in the cluster is current syncing. No need to do it twice
      log.trace("Another instance is already syncing VMs")
      return
    }

    try {
      log.trace("Syncing VMs ...")

      // destroy all virtual machines whose agents have left
      val vmsToRemove = vmRegistry.findVMs(VM.Status.LEFT)
      for (vm in vmsToRemove) {
        log.info("Destroying VM of left agent `${vm.id}' ...")
        vmRegistry.forceSetVMStatus(vm.id, VM.Status.DESTROYING)
        if (vm.externalId != null) {
          cloudClient.destroyVM(vm.externalId, timeoutDestroyVM)
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
              cloudClient.destroyVM(externalId, timeoutDestroyVM)
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
      val unattachedBlockDevices = cloudClient.listAvailableBlockDevices list@{ bd ->
        if (createdByTag != bd[CREATED_BY]) {
          // this is not our block device
          return@list false
        }

        val externalId = bd[VM_EXTERNAL_ID]
        if (externalId != null) {
          val lock = tryLockVM(externalId) ?:
              // someone is currently creating the VM to which the block
              // device will be attached
              return@list false
          lock.release()
        }

        externalId == null || !remainingVMs.contains(externalId)
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
    } finally {
      syncLock.release()
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
    // get a list of a minimum number of setups
    val minimumSetups = setupSelector.selectMinimum(setups, false)

    // get existing VMs
    val vmsPerSetup = mutableMapOf<String, MutableList<VM>>()
    vmRegistry.findNonTerminatedVMs().sortedBy { it.id }.groupByTo(vmsPerSetup) { it.setup.id }

    // Get a minimum number of VMs per setup. Since `vmsPerSetup` is sorted by ID
    // and we take elements from its head, the created list should always contain
    // the same VMs (i.e. we will always send keep-alive messages to the same VMs)
    val vmsToKeep = mutableListOf<VM>()
    for (setup in minimumSetups) {
      val vms = vmsPerSetup[setup.id]
      if (!vms.isNullOrEmpty()) {
        vmsToKeep.add(vms.removeFirst())
      }
    }

    // send keep-alive messages to these VMs
    for (vm in vmsToKeep) {
      val address = REMOTE_AGENT_ADDRESS_PREFIX + vm.id
      val msg = json {
        obj(
            "action" to "keepAlive"
        )
      }
      vertx.eventBus().send(address, msg)
    }
  }

  /**
   * Start a periodic timer that sends keep-alive messages to remote agents
   */
  private fun sendKeepAliveTimer() {
    val milliseconds = config.getString(ConfigConstants.CLOUD_KEEP_ALIVE_INTERVAL, "30s")
        .toDuration().toMillis()
    vertx.setTimer(milliseconds) {
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
    var remaining = n
    while (remaining > 0) {
      // Remove setups whose circuit breaker is open. We have to do this in
      // every iteration because the circuit breaker states can change.
      // 'setupCircuitBreakers' can never have more entries than 'setups' but
      // we need to query all 'setups'. Copy the whole map to local to avoid
      // multiple cluster map requests.
      val cbs = setupCircuitBreakers.getAllBySetup()
      val possibleSetups = setups.filter { cbs[it.id]?.canPerformAttempt ?: true }
      if (possibleSetups.isEmpty()) {
        break
      }

      val result = createRemoteAgent { setupSelector.select(remaining, requiredCapabilities, possibleSetups) }
      remaining = result.count { !it.second }.toLong()
    }
  }

  private suspend fun createRemoteAgent(selector: suspend () -> List<Setup>): List<Pair<VM, Boolean>> {
    // atomically create VM entries in the registry
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLock(LOCK_VMS).await()
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
    return createRemoteAgents(vmsToCreate)
  }

  /**
   * Create virtual machines and deploy remote agents to them based on the
   * given list of registered [vmsToCreate] and their corresponding setups.
   * Return a list that contains pairs of a VM and a boolean telling if the
   * VM was created successfully or not.
   */
  private suspend fun createRemoteAgents(vmsToCreate: List<Pair<VM, Setup>>): List<Pair<VM, Boolean>> {
    val sharedData = vertx.sharedData()
    val deferreds = vmsToCreate.map { (vm, setup) ->
      // create multiple VMs in parallel
      async {
        // hold a lock as long as we are creating this VM
        val creatingLock = sharedData.getLock(VM_CREATION_LOCK_PREFIX + vm.id).await()
        try {
          log.info("Creating virtual machine ${vm.id} with setup `${setup.id}' ...")

          val delay = setupCircuitBreakers.computeIfAbsent(setup).currentDelay
          if (delay > 0) {
            log.info("Backing off for $delay milliseconds due to too many failed attempts.")
            delay(delay)
          }

          try {
            // create VM
            val externalId = createVM(vm.id, setup)
            vmRegistry.setVMExternalID(vm.id, externalId)
            vmRegistry.setVMCreationTime(vm.id, Instant.now())

            // create other volumes in background
            val volumeDeferreds = createVolumesAsync(externalId, setup)

            try {
              cloudClient.waitForVM(externalId, timeoutCreateVM)

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
              cloudClient.destroyVM(externalId, timeoutDestroyVM)
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
            setupCircuitBreakers.afterAttemptPerformed(setup.id, true)
          } catch (t: Throwable) {
            vmRegistry.forceSetVMStatus(vm.id, VM.Status.ERROR)
            vmRegistry.setVMReason(vm.id, t.message ?: "Unknown error")
            vmRegistry.setVMDestructionTime(vm.id, Instant.now())
            setupCircuitBreakers.afterAttemptPerformed(setup.id, false)
            throw t
          }
        } finally {
          creatingLock.release()
        }
      }
    }

    return deferreds.mapIndexed { i, d ->
      vmsToCreate[i].first to try {
        d.await()
        true
      } catch (t: Throwable) {
        log.error("Could not create VM", t)
        false
      }
    }
  }

  /**
   * Create a virtual machine with the given internal [id] and [Setup] and
   * return its external ID
   */
  private suspend fun createVM(id: String, setup: Setup): String {
    val metadata = mapOf(CREATED_BY to createdByTag, SETUP_ID to setup.id)
    val metadataBlockDevice = metadata + (VM_EXTERNAL_ID to id)

    val name = "fraunhofer-steep-${id}"
    val imageId = cloudClient.getImageID(setup.imageName)
    val blockDeviceId = cloudClient.createBlockDevice(setup.blockDeviceSizeGb,
        setup.blockDeviceVolumeType, imageId, true, setup.availabilityZone,
        metadataBlockDevice)
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
      if (msg.body() == vmId) {
        promise.complete()
      }
    }

    log.info("Provisioning server $ipAddress ...")

    val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .newLineTrimming(false)
        .extension(ProvisioningTemplateExtension(ssh))
        .build()
    val context = mapOf<String, Any>(
        "config" to config.map,
        "env" to System.getenv(),
        "ipAddress" to ipAddress,
        "agentId" to vmId,
        "agentCapabilities" to setup.providedCapabilities, // TODO deprecated - it's available through `setup` now
        "setup" to setup
    )

    // run provisioning scripts
    for (script in setup.provisioningScripts) {
      val destFileName = "/tmp/" + FilenameUtils.getName(script)

      // compile script template and write result into temporary file
      val tmpFile = vertx.executeBlocking({ ebp ->
        val compiledTemplate = engine.getTemplate(script)
        val writer = StringWriter()
        compiledTemplate.evaluate(writer, context)

        val f = File.createTempFile("job", null)
        f.deleteOnExit()
        try {
          f.writeText(writer.toString())
          ebp.complete(f)
        } catch (t: Throwable) {
          f.delete()
          throw t
        }
      }, false).await()!!

      // upload compiled script
      try {
        ssh.uploadFile(tmpFile.absolutePath, destFileName)
      } finally {
        tmpFile.delete()
      }

      // execute script
      ssh.execute("sudo chmod +x $destFileName")
      ssh.execute("sudo $destFileName")
    }

    // throw if the agent does not become available after a set amount of time
    val timeout = timeoutAgentReady.toMillis()
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
    val retrySeconds = 2
    val deadline = Instant.now().plus(timeoutSshReady)

    while (true) {
      cloudClient.waitForVM(externalId, timeoutCreateVM)

      log.info("Waiting for SSH: $ipAddress")

      try {
        ssh.tryConnect(retrySeconds)
        break
      } catch (e: IOException) {
        delay(min(deadline.toEpochMilli() - Instant.now().toEpochMilli(),
            retrySeconds * 1000L))
        val now = Instant.now()
        if (now.isAfter(deadline) || now == deadline) {
          throw IllegalStateException("Too many attempts to connect to SSH")
        }
      }
    }
  }
}
