package db

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Tuple
import model.cloud.VM
import java.time.Instant

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
    private const val CREATION_TIME = "creationTime"
    private const val AGENT_JOIN_TIME = "agentJoinTime"
    private const val DESTRUCTION_TIME = "destructionTime"
    private const val STATUS = "status"
    private const val REASON = "reason"
  }

  override suspend fun addVM(vm: VM) {
    val statement = "INSERT INTO $VMS ($ID, $DATA) VALUES ($1, $2)"
    val params = Tuple.of(vm.id, JsonUtils.toJson(vm))
    client.preparedQuery(statement).execute(params).await()
  }

  override suspend fun findVMs(status: VM.Status?, size: Int, offset: Int,
      order: Int): Collection<VM> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()
    val statement = StringBuilder("SELECT $DATA::varchar FROM $VMS ")

    val params = if (status != null) {
      statement.append("WHERE $DATA->'$STATUS'=$1 ")
      Tuple.of(status.toString())
    } else {
      null
    }

    statement.append("ORDER BY $SERIAL $asc LIMIT $limit OFFSET $offset")

    val rs = if (params == null) {
      client.query(statement.toString()).execute().await()
    } else {
      client.preparedQuery(statement.toString()).execute(params).await()
    }
    return rs.map { JsonUtils.mapper.readValue(it.getString(0)) }
  }

  override suspend fun findVMById(id: String): VM? {
    val statement = "SELECT $DATA::varchar FROM $VMS WHERE $ID=$1"
    val params = Tuple.of(id)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.firstOrNull()?.let { JsonUtils.mapper.readValue(it.getString(0)) }
  }

  override suspend fun findVMByExternalId(externalId: String): VM? {
    val statement = "SELECT $DATA::varchar FROM $VMS WHERE $DATA->'$EXTERNAL_ID'=$1"
    val params = Tuple.of(externalId)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.firstOrNull()?.let { JsonUtils.mapper.readValue(it.getString(0)) }
  }

  override suspend fun findNonTerminatedVMs(): Collection<VM> {
    val statement = "SELECT $DATA::varchar FROM $VMS WHERE $DATA->'$STATUS'!=$1 " +
        "AND $DATA->'$STATUS'!=$2"
    val params = Tuple.of(VM.Status.DESTROYED.toString(), VM.Status.ERROR.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.map { JsonUtils.mapper.readValue(it.getString(0)) }
  }

  override suspend fun countVMs(status: VM.Status?): Long {
    val statement = StringBuilder("SELECT COUNT(*) FROM $VMS")

    val params = if (status != null) {
      statement.append(" WHERE $DATA->'$STATUS'=$1")
      Tuple.of(status.toString())
    } else {
      null
    }

    val rs = if (params == null) {
      client.query(statement.toString()).execute().await()
    } else {
      client.preparedQuery(statement.toString()).execute(params).await()
    }
    return rs.firstOrNull()?.getLong(0) ?: 0L
  }

  override suspend fun countNonTerminatedVMsBySetup(setupId: String): Long {
    val statement = "SELECT COUNT(*) FROM $VMS WHERE $DATA->'$SETUP'->'$ID'=$1 " +
        "AND $DATA->'$STATUS'!=$2 AND $DATA->'$STATUS'!=$3"
    val params = Tuple.of(setupId, VM.Status.DESTROYED.toString(),
        VM.Status.ERROR.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs?.firstOrNull()?.getLong(0) ?: 0L
  }

  override suspend fun countStartingVMsBySetup(setupId: String): Long {
    val statement = "SELECT COUNT(*) FROM $VMS WHERE $DATA->'$SETUP'->'$ID'=$1 " +
        "AND ($DATA->'$STATUS'=$2 OR $DATA->'$STATUS'=$3)"
    val params = Tuple.of(setupId, VM.Status.CREATING.toString(),
        VM.Status.PROVISIONING.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs?.firstOrNull()?.getLong(0) ?: 0L
  }

  override suspend fun setVMCreationTime(id: String, creationTime: Instant) {
    val newObj = jsonObjectOf(
        CREATION_TIME to creationTime
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMAgentJoinTime(id: String, agentJoinTime: Instant) {
    val newObj = jsonObjectOf(
        AGENT_JOIN_TIME to agentJoinTime
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMDestructionTime(id: String, destructionTime: Instant) {
    val newObj = jsonObjectOf(
        DESTRUCTION_TIME to destructionTime
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMStatus(id: String, currentStatus: VM.Status,
      newStatus: VM.Status) {
    val updateStatement = "UPDATE $VMS SET $DATA=$DATA || $1 WHERE $ID=$2 " +
        "AND $DATA->'$STATUS'=$3"
    val newObj = jsonObjectOf(
        STATUS to newStatus.toString()
    )
    val updateParams = Tuple.of(newObj, id, currentStatus.toString())
    client.preparedQuery(updateStatement).execute(updateParams).await()
  }

  override suspend fun forceSetVMStatus(id: String, newStatus: VM.Status) {
    val newObj = jsonObjectOf(
        STATUS to newStatus.toString()
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun getVMStatus(id: String): VM.Status {
    val statement = "SELECT $DATA->'$STATUS' FROM $VMS WHERE $ID=$1"
    val params = Tuple.of(id)
    val rs = client.preparedQuery(statement).execute(params).await().firstOrNull() ?:
        throw NoSuchElementException("There is no VM with ID `$id'")
    return VM.Status.valueOf(rs.getString(0))
  }

  override suspend fun setVMExternalID(id: String, externalId: String) {
    val newObj = jsonObjectOf(
        EXTERNAL_ID to externalId
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMIPAddress(id: String, ipAddress: String) {
    val newObj = jsonObjectOf(
        IP_ADDRESS to ipAddress
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun setVMReason(id: String, reason: String?) {
    val newObj = jsonObjectOf(
        REASON to reason
    )
    updateProperties(VMS, id, newObj)
  }

  override suspend fun deleteVMsDestroyedBefore(timestamp: Instant): Collection<String> {
    return withConnection { connection ->
      val statement1 = "DELETE FROM $VMS WHERE $DATA->'$DESTRUCTION_TIME' < $1 " +
          "RETURNING $ID"
      val params1 = Tuple.of(timestamp.toString())
      val rs1 = connection.preparedQuery(statement1).execute(params1).await()
      val ids1 = rs1.map { it.getString(0) }

      // find IDs of terminated VMs that do not have a destructionTime but
      // whose ID was created before the given timestamp (this will also
      // include VMs without a creationTime)
      val statement2 = "SELECT $ID FROM $VMS WHERE $DATA->'$DESTRUCTION_TIME' IS NULL " +
          "AND ($DATA->'$STATUS'=$1 OR $DATA->'$STATUS'=$2)"
      val params2 = Tuple.of(VM.Status.DESTROYED.toString(), VM.Status.ERROR.toString())
      val rs2 = connection.preparedQuery(statement2).execute(params2).await()
      val ids2 = rs2.map { it.getString(0) }
        .filter { Instant.ofEpochMilli(UniqueID.toMillis(it)).isBefore(timestamp) }

      // then delete those VMs
      val statement3 = "DELETE FROM $VMS WHERE $ID=ANY($1)"
      val params3 = Tuple.of(ids2.toTypedArray())
      connection.preparedQuery(statement3).execute(params3).await()

      ids1 + ids2
    }
  }
}
