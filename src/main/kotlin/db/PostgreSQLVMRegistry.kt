package db

import io.vertx.core.Vertx
import model.cloud.VM

/**
 * A VM registry that keeps objects in a PostgreSQL database
 * @param vertx the current Vert.x instance
 * @param url the JDBC url to the database
 * @param username the username
 * @param password the password
 * @author Michel Kraemer
 */
class PostgreSQLVMRegistry(private val vertx: Vertx, url: String,
    username: String, password: String) : VMRegistry {
  override suspend fun close() {
    TODO("Not yet implemented")
  }

  override suspend fun addVM(vm: VM) {
    TODO("Not yet implemented")
  }

  override suspend fun findVMs(size: Int, offset: Int, order: Int): Collection<VM> {
    TODO("Not yet implemented")
  }

  override suspend fun findVMById(id: String): VM? {
    TODO("Not yet implemented")
  }

  override suspend fun findVMByExternalId(externalId: String): VM? {
    TODO("Not yet implemented")
  }

  override suspend fun findVMsByStatus(status: VM.Status): Collection<VM> {
    TODO("Not yet implemented")
  }

  override suspend fun findNonTerminatedVMs(): Collection<VM> {
    TODO("Not yet implemented")
  }

  override suspend fun countNonTerminatedVMsBySetup(setupId: String): Long {
    TODO("Not yet implemented")
  }

  override suspend fun setVMStatus(id: String, currentStatus: VM.Status,
      newStatus: VM.Status): Boolean {
    TODO("Not yet implemented")
  }

  override suspend fun forceSetVMStatus(id: String, newStatus: VM.Status) {
    TODO("Not yet implemented")
  }

  override suspend fun setVMExternalID(id: String, externalId: String) {
    TODO("Not yet implemented")
  }

  override suspend fun setVMIPAddress(id: String, ipAddress: String) {
    TODO("Not yet implemented")
  }

  override suspend fun setVMErrorMessage(id: String, errorMessage: String?) {
    TODO("Not yet implemented")
  }
}
