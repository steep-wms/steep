package db

import AddressConstants
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.kotlin.core.eventbus.deliveryOptionsOf
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import model.cloud.VM
import java.time.Instant

/**
 * Wraps around a VM registry and published events whenever the
 * registry's contents have changed.
 * @author Michel Kraemer
 */
class NotifyingVMRegistry(private val delegate: VMRegistry, private val vertx: Vertx) :
    VMRegistry by delegate {
  override suspend fun addVM(vm: VM) {
    delegate.addVM(vm)
    vertx.eventBus().publish(AddressConstants.VM_ADDED, {
      JsonUtils.toJson(vm)
    }, deliveryOptionsOf(codecName = "lazyjsonobject"))
  }

  override suspend fun setVMCreationTime(id: String, creationTime: Instant) {
    delegate.setVMCreationTime(id, creationTime)
    vertx.eventBus().publish(AddressConstants.VM_CREATIONTIME_CHANGED, json {
      obj(
          "id" to id,
          "creationTime" to creationTime
      )
    })
  }

  override suspend fun setVMAgentJoinTime(id: String, agentJoinTime: Instant) {
    delegate.setVMAgentJoinTime(id, agentJoinTime)
    vertx.eventBus().publish(AddressConstants.VM_AGENTJOINTIME_CHANGED, json {
      obj(
          "id" to id,
          "agentJoinTime" to agentJoinTime
      )
    })
  }

  override suspend fun setVMDestructionTime(id: String, destructionTime: Instant) {
    delegate.setVMDestructionTime(id, destructionTime)
    vertx.eventBus().publish(AddressConstants.VM_DESTRUCTIONTIME_CHANGED, json {
      obj(
          "id" to id,
          "destructionTime" to destructionTime
      )
    })
  }

  override suspend fun setVMStatus(id: String, currentStatus: VM.Status, newStatus: VM.Status) {
    delegate.setVMStatus(id, currentStatus, newStatus)
    val actualStatus = try {
      delegate.getVMStatus(id)
    } catch (e: NoSuchElementException) {
      // setVMStatus is a noop if the VM does not exist
      return
    }
    if (actualStatus == newStatus) {
      vertx.eventBus().publish(AddressConstants.VM_STATUS_CHANGED, json {
        obj(
            "id" to id,
            "status" to newStatus.name
        )
      })
    }
  }

  override suspend fun forceSetVMStatus(id: String, newStatus: VM.Status) {
    delegate.forceSetVMStatus(id, newStatus)
    vertx.eventBus().publish(AddressConstants.VM_STATUS_CHANGED, json {
      obj(
          "id" to id,
          "status" to newStatus.name
      )
    })
  }

  override suspend fun setVMExternalID(id: String, externalId: String) {
    delegate.setVMExternalID(id, externalId)
    vertx.eventBus().publish(AddressConstants.VM_EXTERNALID_CHANGED, json {
      obj(
          "id" to id,
          "externalId" to externalId
      )
    })
  }

  override suspend fun setVMIPAddress(id: String, ipAddress: String) {
    delegate.setVMIPAddress(id, ipAddress)
    vertx.eventBus().publish(AddressConstants.VM_IPADDRESS_CHANGED, json {
      obj(
          "id" to id,
          "ipAddress" to ipAddress
      )
    })
  }

  override suspend fun setVMReason(id: String, reason: String?) {
    delegate.setVMReason(id, reason)
    vertx.eventBus().publish(AddressConstants.VM_REASON_CHANGED, json {
      obj(
          "id" to id,
          "reason" to reason
      )
    })
  }
}
