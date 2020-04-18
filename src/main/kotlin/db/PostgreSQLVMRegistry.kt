package db

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.sql.queryAwait
import io.vertx.kotlin.ext.sql.querySingleWithParamsAwait
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import io.vertx.kotlin.ext.sql.updateWithParamsAwait
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
    username: String, password: String) : PostgreSQLRegistry(vertx, url, username, password),
    VMRegistry {
  companion object {
    /**
     * Table and column names
     */
    private const val VMS = "vms"
    private const val SERIAL = "serial"
    private const val EXTERNAL_ID = "externalId"
    private const val IP_ADDRESS = "ipAddress"
    private const val SETUP = "setup"
    private const val STATUS = "status"
    private const val ERROR_MESSAGE = "errorMessage"
  }

  override suspend fun addVM(vm: VM) {
    withConnection { connection ->
      val statement = "INSERT INTO $VMS ($ID, $DATA) VALUES (?, ?::jsonb)"
      val params = json {
        array(
            vm.id,
            JsonUtils.mapper.writeValueAsString(vm)
        )
      }
      connection.updateWithParamsAwait(statement, params)
    }
  }

  override suspend fun findVMs(size: Int, offset: Int, order: Int): Collection<VM> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()
    return withConnection { connection ->
      val rs = connection.queryAwait("SELECT $DATA FROM $VMS " +
          "ORDER BY $SERIAL $asc LIMIT $limit OFFSET $offset")
      rs.results.map { JsonUtils.mapper.readValue<VM>(it.getString(0)) }
    }
  }

  override suspend fun findVMById(id: String): VM? {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $VMS WHERE $ID=?"
      val params = json {
        array(
            id
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { JsonUtils.mapper.readValue<VM>(it.getString(0)) }
    }
  }

  override suspend fun findVMByExternalId(externalId: String): VM? {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $VMS WHERE $DATA->'$EXTERNAL_ID'=?::jsonb"
      val params = json {
        array(
            JsonUtils.mapper.writeValueAsString(externalId)
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { JsonUtils.mapper.readValue<VM>(it.getString(0)) }
    }
  }

  override suspend fun findVMsByStatus(status: VM.Status): Collection<VM> {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $VMS WHERE $DATA->'$STATUS'=?::jsonb"
      val params = json {
        array(
            "\"$status\""
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { JsonUtils.mapper.readValue<VM>(it.getString(0)) }
    }
  }

  override suspend fun findNonTerminatedVMs(): Collection<VM> {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $VMS WHERE $DATA->'$STATUS'!=?::jsonb " +
          "AND $DATA->'$STATUS'!=?::jsonb"
      val params = json {
        array(
            "\"${VM.Status.DESTROYED}\"",
            "\"${VM.Status.ERROR}\""
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { JsonUtils.mapper.readValue<VM>(it.getString(0)) }
    }
  }

  override suspend fun countNonTerminatedVMsBySetup(setupId: String): Long {
    return withConnection { connection ->
      val statement = "SELECT COUNT(*) FROM $VMS WHERE $DATA->'$SETUP'->'$ID'=?::jsonb " +
          "AND $DATA->'$STATUS'!=?::jsonb AND $DATA->'$STATUS'!=?::jsonb"
      val params = json {
        array(
            JsonUtils.mapper.writeValueAsString(setupId),
            "\"${VM.Status.DESTROYED}\"",
            "\"${VM.Status.ERROR}\""
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.getLong(0) ?: 0L
    }
  }

  override suspend fun setVMStatus(id: String, currentStatus: VM.Status,
      newStatus: VM.Status) {
    withConnection { connection ->
      val updateStatement = "UPDATE $VMS SET $DATA=$DATA || ?::jsonb WHERE $ID=? " +
          "AND $DATA->'$STATUS'=?::jsonb"
      val newObj = json {
        obj(
            STATUS to newStatus.toString()
        )
      }
      val updateParams = json {
        array(
            newObj.encode(),
            id,
            "\"$currentStatus\""
        )
      }
      connection.updateWithParamsAwait(updateStatement, updateParams)
    }
  }

  override suspend fun forceSetVMStatus(id: String, newStatus: VM.Status) {
    val newObj = json {
      obj(
          STATUS to newStatus.toString()
      )
    }
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMExternalID(id: String, externalId: String) {
    val newObj = json {
      obj(
          EXTERNAL_ID to externalId
      )
    }
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMIPAddress(id: String, ipAddress: String) {
    val newObj = json {
      obj(
          IP_ADDRESS to ipAddress
      )
    }
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMErrorMessage(id: String, errorMessage: String?) {
    val newObj = json {
      obj(
          ERROR_MESSAGE to errorMessage
      )
    }
    updateProperties(VMS, id, newObj)
  }
}
