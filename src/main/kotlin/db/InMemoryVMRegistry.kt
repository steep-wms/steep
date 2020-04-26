package db

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.shareddata.AsyncMap
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.core.shareddata.getLockAwait
import io.vertx.kotlin.core.shareddata.putAwait
import io.vertx.kotlin.core.shareddata.sizeAwait
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.awaitResult
import model.cloud.VM
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A VM registry that keeps objects in memory
 * @param vertx the current Vert.x instance
 * @author Michel Kraemer
 */
class InMemoryVMRegistry(private val vertx: Vertx) : VMRegistry {
  companion object {
    /**
     * Name of a cluster-wide map keeping [VM]s
     */
    private const val ASYNC_MAP_VMS = "InMemoryVMRegistry.VMs"

    /**
     * Name of a cluster-wide lock used to make atomic operations on the
     * cluster-wide map of VMs
     */
    private const val LOCK_VMS = "InMemoryVMRegistry.VMs.Lock"
  }

  private data class VMEntry(
      val serial: Int,
      val vm: VM
  )

  private val vmEntryID = AtomicInteger()

  private val vms: Future<AsyncMap<String, String>>

  init {
    val sharedData = vertx.sharedData()
    vms = Future.future()
    sharedData.getAsyncMap(ASYNC_MAP_VMS, vms)
  }

  override suspend fun close() {
    // nothing to do here
  }

  override suspend fun addVM(vm: VM) {
    val entry = VMEntry(vmEntryID.getAndIncrement(), vm)
    val str = JsonUtils.mapper.writeValueAsString(entry)
    vms.await().putAwait(vm.id, str)
  }

  override suspend fun findVMs(size: Int, offset: Int, order: Int): Collection<VM> {
    val map = vms.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.mapper.readValue<VMEntry>(it) }
        .sortedBy { it.serial }
        .let { if (order < 0) it.reversed() else it }
        .drop(offset)
        .let { if (size >= 0) it.take(size) else it }
        .map { it.vm }
  }

  private suspend fun findVMEntryById(id: String): VMEntry? {
    return vms.await().getAwait(id)?.let {
      JsonUtils.mapper.readValue<VMEntry>(it)
    }
  }

  override suspend fun findVMById(id: String) =
      findVMEntryById(id)?.vm

  override suspend fun findVMByExternalId(externalId: String): VM? {
    val map = vms.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.mapper.readValue<VMEntry>(it) }
        .find { it.vm.externalId == externalId }?.vm
  }

  override suspend fun findVMsByStatus(status: VM.Status): Collection<VM> {
    val map = vms.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.mapper.readValue<VMEntry>(it) }
        .filter { it.vm.status == status }
        .map { it.vm }
  }

  override suspend fun findNonTerminatedVMs(): Collection<VM> {
    val map = vms.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.mapper.readValue<VMEntry>(it) }
        .filter { it.vm.status != VM.Status.DESTROYED && it.vm.status != VM.Status.ERROR }
        .map { it.vm }
  }

  override suspend fun countVMs(): Long {
    return vms.await().sizeAwait().toLong()
  }

  override suspend fun countNonTerminatedVMsBySetup(setupId: String): Long {
    val map = vms.await()
    val values = awaitResult<List<String>> { map.values(it) }
    return values
        .map { JsonUtils.mapper.readValue<VMEntry>(it) }
        .count { it.vm.status != VM.Status.DESTROYED && it.vm.status != VM.Status.ERROR &&
            it.vm.setup.id == setupId }.toLong()
  }

  override suspend fun setVMStatus(id: String, currentStatus: VM.Status,
      newStatus: VM.Status) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_VMS)
    try {
      val entry = findVMEntryById(id) ?: return
      if (entry.vm.status == currentStatus) {
        val newEntry = entry.copy(vm = entry.vm.copy(status = newStatus))
        vms.await().putAwait(entry.vm.id, JsonUtils.mapper.writeValueAsString(newEntry))
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun updateVMEntry(id: String, updater: (VMEntry) -> VMEntry) {
    val sharedData = vertx.sharedData()
    val lock = sharedData.getLockAwait(LOCK_VMS)
    try {
      val map = vms.await()
      map.getAwait(id)?.let {
        val oldEntry = JsonUtils.mapper.readValue<VMEntry>(it)
        val newEntry = updater(oldEntry)
        map.putAwait(id, JsonUtils.mapper.writeValueAsString(newEntry))
      }
    } finally {
      lock.release()
    }
  }

  private suspend fun updateVM(id: String, updater: (VM) -> VM) {
    updateVMEntry(id) { it.copy(vm = updater(it.vm)) }
  }

  override suspend fun setVMCreationTime(id: String, creationTime: Instant) {
    updateVM(id) { it.copy(creationTime = creationTime) }
  }

  override suspend fun setVMAgentJoinTime(id: String, agentJoinTime: Instant) {
    updateVM(id) { it.copy(agentJoinTime = agentJoinTime) }
  }

  override suspend fun setVMDestructionTime(id: String, destructionTime: Instant) {
    updateVM(id) { it.copy(destructionTime = destructionTime) }
  }

  override suspend fun forceSetVMStatus(id: String, newStatus: VM.Status) {
    updateVM(id) { it.copy(status = newStatus) }
  }

  override suspend fun getVMStatus(id: String): VM.Status {
    return findVMById(id)?.status ?: throw NoSuchElementException(
        "There is no VM with ID `$id'")
  }

  override suspend fun setVMExternalID(id: String, externalId: String) {
    updateVM(id) { it.copy(externalId = externalId) }
  }

  override suspend fun setVMIPAddress(id: String, ipAddress: String) {
    updateVM(id) { it.copy(ipAddress = ipAddress) }
  }

  override suspend fun setVMReason(id: String, reason: String?) {
    updateVM(id) { it.copy(reason = reason) }
  }
}
