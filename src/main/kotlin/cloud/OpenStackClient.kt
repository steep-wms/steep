package cloud

import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.WorkerExecutor
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.openstack4j.api.Builders
import org.openstack4j.api.OSClient
import org.openstack4j.core.transport.Config
import org.openstack4j.model.common.Identifier
import org.openstack4j.model.compute.Server
import org.openstack4j.model.storage.block.Volume
import org.openstack4j.openstack.OSFactory
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * Provides methods to access an OpenStack cloud
 * @param endpoint the authentication endpoint
 * @param username the username used for authentication
 * @param password the password used for authentication
 * @param domainName the domain name used for authentication
 * @param projectId the ID of the OpenStack project to connect to. Either
 * `projectId` or `projectName` must be set but not both at the same time.
 * @param projectName the name of the OpenStack project to connect to. Will be
 * used in combination with `domainName` if `projectId` is not set.
 * @param networkId the ID of the OpenStack network to attach new VMs to
 * @param usePublicIp `true` if new VMs should have a public IP address
 * @param securityGroups the OpenStack security groups that new VMs should be
 * put in
 * @param keypairName the OpenStack keypair to deploy to new VMs
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class OpenStackClient(endpoint: String, username: String, password: String,
    domainName: String, projectId: String?, projectName: String?,
    private val networkId: String, private val usePublicIp: Boolean,
    private val securityGroups: List<String>, private val keypairName: String,
    vertx: Vertx) : CloudClient, CoroutineScope {
  companion object {
    private val log = LoggerFactory.getLogger(OpenStackClient::class.java)
  }

  override val coroutineContext: CoroutineContext = vertx.dispatcher()

  /**
   * A worker executor with one thread only, so the OpenStack actions always
   * run in the same thread
   */
  private val executor: WorkerExecutor = vertx.createSharedWorkerExecutor(
      OpenStackClient::class.simpleName, 1)

  /**
   * The actual OpenStack client
   */
  private val os: Deferred<OSClient.OSClientV3>

  init {
    // create actual OpenStack client and connect to the server
    os = async {
      var builder = OSFactory.builderV3()
          .endpoint(endpoint)
          .withConfig(Config.newConfig()
              .withConnectionTimeout(1000 * 30) // 30s
              .withReadTimeout(1000 * 30)) // 30s
          .credentials(username, password, Identifier.byName(domainName))
      builder = if (projectId != null) {
        builder.scopeToProject(Identifier.byId(projectId))
      } else {
        builder.scopeToProject(Identifier.byName(projectName),
            Identifier.byName(domainName))
      }

      blocking { builder.authenticate() }
    }
  }

  /**
   * Execute a block through the worker [executor]
   * @param block the block to execute
   * @return the block's return value
   */
  private suspend fun <T> blocking(block: () -> T): T {
    return awaitResult { handler ->
      executor.executeBlocking<T>({ fut ->
        fut.complete(block())
      }, { ar ->
        handler.handle(ar)
      })
    }
  }

  override suspend fun listAvailableBlockDevices(metadataFilter: (suspend (Map<String, String>) ->
      Boolean)?): List<String> {
    val os = this.os.await()
    val volumes = blocking { os.blockStorage().volumes().list() }
    val availableVolumes = volumes.filter { it.status == Volume.Status.AVAILABLE }
    return if (metadataFilter == null) {
      availableVolumes.map { it.id }
    } else {
      availableVolumes.filter { metadataFilter(it.metaData) }.map { it.id }
    }
  }

  override suspend fun listVMs(metadataFilter: ((Map<String, String>) -> Boolean)?): List<String> {
    val os = this.os.await()
    val servers = blocking { os.compute().servers().list() }
    return if (metadataFilter == null) {
      servers.map { it.id }
    } else {
      servers.filter { metadataFilter(it.metadata) }.map { it.id }
    }
  }

  override suspend fun getImageID(name: String): String {
    val os = this.os.await()
    val images = blocking { os.imagesV2().list(mapOf("name" to name)) }
    if (images.size > 1) {
      throw IllegalStateException("Found more than one image with the name `$name'")
    } else if (images.isEmpty()) {
      throw NoSuchElementException("Could not find image `$name'")
    }
    return images[0].id
  }

  override suspend fun createBlockDevice(blockDeviceSizeGb: Int,
      volumeType: String?, imageId: String?, bootable: Boolean,
      availabilityZone: String, metadata: Map<String, String>): String {
    log.info("Creating volume ...")

    val builder = Builders.volume()
        .name("fraunhofer-steep-" + UniqueID.next())
        .metadata(metadata)
        .size(blockDeviceSizeGb)
        .volumeType(volumeType)
        .bootable(bootable)
        .zone(availabilityZone)

    if (imageId != null) {
      builder.imageRef(imageId)
    }

    val os = this.os.await()
    val volume = blocking { os.blockStorage().volumes().create(builder.build()) } ?:
        throw IllegalStateException("Could not create volume due to an unknown error")

    log.info("Created volume: $volume")

    waitForVolume(volume)

    log.info("Volume `${volume.id}' is available")
    return volume.id
  }

  /**
   * Wait for a volume to become available to be mounted
   * @param volume the volume
   */
  private suspend fun waitForVolume(volume: Volume) {
    val os = this.os.await()
    var myVolume = volume
    while (true) {
      val status = myVolume.status
      if (status != Volume.Status.CREATING &&
          status != Volume.Status.DOWNLOADING) {
        if (status != Volume.Status.AVAILABLE) {
          throw IllegalStateException("Unable to handle volume status `$status'")
        }
        break
      }

      log.info("Waiting for volume `${myVolume.id}'. Status is `$status' ...")
      delay(2000)

      myVolume = blocking { os.blockStorage().volumes().get(myVolume.id) } ?:
          throw NoSuchElementException("Volume does not exist anymore")
    }
  }

  override suspend fun destroyBlockDevice(id: String) {
    log.info("Destroying block device `$id' ...")

    val os = this.os.await()
    val v = blocking { os.blockStorage().volumes().get(id) }
    if (v == null) {
      log.info("Block device does not exist")
      return
    }

    if (v.status == Volume.Status.DELETING) {
      log.info("Block device is already being deleted")
      return
    }

    val response = blocking { os.blockStorage().volumes().delete(id) }
    if (!response.isSuccess) {
      if (response.fault != null && response.fault.startsWith("VolumeNotFound")) {
        // ignore error
        log.info("Block device does not exist")
        return
      }
      log.error("Could not delete block device: " + response.fault)
      throw IllegalStateException(response.fault)
    }
  }

  override suspend fun createVM(name: String, flavor: String, blockDeviceId: String,
      availabilityZone: String, metadata: Map<String, String>): String {
    log.info("Creating VM ...")

    var builder = Builders.server()
        .name(name)
        .addMetadata(metadata)
        .networks(listOf(networkId))
        .keypairName(keypairName)
        .flavor(flavor)
        .availabilityZone(availabilityZone)
        .blockDevice(Builders.blockDeviceMapping()
            .deleteOnTermination(true)
            .uuid(blockDeviceId)
            .bootIndex(0)
            .build())
    for (securityGroup in securityGroups) {
      builder = builder.addSecurityGroup(securityGroup)
    }

    val os = this.os.await()
    val server = blocking { os.compute().servers().boot(builder.build()) }

    log.info("Created VM: $server")

    return server.id
  }

  override suspend fun isVMActive(vmId: String): Boolean {
    val os = this.os.await()
    val server = blocking { os.compute().servers().get(vmId) } ?:
        throw NoSuchElementException("VM does not exist")
    return server.status == Server.Status.ACTIVE
  }

  override suspend fun waitForVM(vmId: String, timeout: Duration?) {
    val os = this.os.await()
    val server = blocking { os.compute().servers().get(vmId) } ?:
        throw NoSuchElementException("VM does not exist")
    waitForServer(server, timeout)
  }

  /**
   * Wait for a server to be available
   * @param server the server
   * @param timeout the maximum time to wait
   */
  private suspend fun waitForServer(server: Server, timeout: Duration?) {
    val os = this.os.await()
    var myServer = server
    val deadline = timeout?.let { Instant.now().plus(it) }
    while (true) {
      val fault = myServer.fault
      if (fault != null) {
        log.error("VM is faulty: (${fault.code}) ${fault.message}. [${fault.details}]")
        throw IllegalStateException(fault.message)
      }

      val status = myServer.status
      if (status == Server.Status.ACTIVE) {
        break
      }

      log.info("Waiting for VM `${myServer.id}'. Status is `$status' ...")

      val d = (if (deadline != null)
        deadline.toEpochMilli() - Instant.now().toEpochMilli()
      else
        2000).coerceAtMost(2000)
      delay(d)

      val now = Instant.now()
      if (deadline != null && (now.isAfter(deadline) || now == deadline)) {
        log.error("Timed out waiting for VM ${server.id} to become available")
        throw IllegalStateException("Timed out waiting for VM ${server.id} " +
            "to become available")
      }

      myServer = blocking { os.compute().servers().get(myServer.id) } ?:
          throw NoSuchElementException("VM does not exist anymore")
    }
  }

  override suspend fun destroyVM(id: String, timeout: Duration?) {
    val os = this.os.await()

    val server = blocking { os.compute().servers().get(id) }
    if (server == null) {
      log.info("VM does not exist")
      return
    }

    // delete all floating IPs
    if (server.addresses != null && server.addresses.addresses != null) {
      val floatingIPs = async(start = CoroutineStart.LAZY) {
        blocking { os.compute().floatingIps().list() }
      }

      val addresses = server.addresses.addresses
          .flatMap { it.value }
          .filter { "floating" == it.type }

      for (a in addresses) {
        for (ip in floatingIPs.await()) {
          if (ip.floatingIpAddress == a.addr) {
            log.info("Deallocating floating IP `${ip.floatingIpAddress}' ...")
            val r = blocking { os.compute().floatingIps().deallocateIP(ip.id) }
            if (!r.isSuccess) {
              log.error("Could not deallocate floating IP: " + r.fault)
              throw IllegalStateException(r.fault)
            }
          }
        }
      }
    }

    log.info("Destroying VM `$id' ...")

    val response = blocking { os.compute().servers().delete(server.id) }
    if (!response.isSuccess) {
      log.error("Could not destroy VM: " + response.fault)
      throw IllegalStateException(response.fault)
    }

    val deadline = timeout?.let { Instant.now().plus(it) }
    do {
      log.info("Waiting for VM `${server.id}' to be destroyed")

      val d = (if (deadline != null)
        deadline.toEpochMilli() - Instant.now().toEpochMilli()
      else
        2000).coerceAtMost(2000)
      delay(d)

      val now = Instant.now()
      if (deadline != null && (now.isAfter(deadline) || now == deadline)) {
        log.error("Timed out waiting for VM ${server.id} to be destroyed")
        throw IllegalStateException("Timed out waiting for VM ${server.id} to be destroyed")
      }
    } while (blocking { os.compute().servers().get(server.id) } != null)

    log.info("VM successfully destroyed")
  }

  override suspend fun getIPAddress(vmId: String): String {
    val os = this.os.await()
    val server = blocking { os.compute().servers().get(vmId) } ?:
        throw NoSuchElementException("VM does not exist")

    var type = "fixed"
    if (usePublicIp) {
      type = "floating"
    }

    val ip = server.addresses?.addresses
        ?.flatMap { it.value }
        ?.find { it.type == type }

    if (ip != null) {
      return ip.addr
    }

    if (!usePublicIp) {
      throw IllegalStateException("Could not find IP address of VM `$vmId'")
    }

    return assignFloatingIp(server)
  }

  /**
   * Assign a floating IP to a server
   * @param server the server
   * @return the new floating IP
   */
  private suspend fun assignFloatingIp(server: Server): String {
    val os = this.os.await()
    val poolNames = blocking { os.compute().floatingIps().poolNames }

    log.info("Allocating floating IP from pool `${poolNames[0]}' ...")
    val floatingIp = blocking { os.compute().floatingIps().allocateIP(poolNames[0]) }

    try {
      log.info("Adding floating IP `${floatingIp.floatingIpAddress}' " +
          "to server `${server.id}' ...")
      val response = blocking { os.compute().floatingIps().addFloatingIP(
          server, floatingIp.floatingIpAddress) }

      if (!response.isSuccess) {
        log.error("Could not assign floating ip: " + response.fault)
        throw IllegalStateException(response.fault)
      }

      return floatingIp.floatingIpAddress
    } catch (e: Exception) {
      blocking { os.compute().floatingIps().deallocateIP(floatingIp.id) }
      throw e
    }
  }

  override suspend fun attachVolume(vmId: String, volumeId: String) {
    log.info("Attaching volume `$volumeId' to VM `$vmId' ...")
    val os = this.os.await()
    blocking { os.compute().servers().attachVolume(vmId, volumeId, null) }
  }
}
