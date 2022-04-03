package db

import com.google.common.cache.CacheBuilder
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import model.Submission
import model.processchain.ProcessChain
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * A submission registry that keeps objects in a PostgreSQL database
 * @param vertx the current Vert.x instance
 * @param url the JDBC url to the database
 * @param username the username
 * @param password the password
 * @author Michel Kraemer
 */
class PostgreSQLSubmissionRegistry(private val vertx: Vertx, url: String,
    username: String, password: String) : PostgreSQLRegistry(vertx, url, username, password),
    SubmissionRegistry {
  companion object {
    /**
     * Table and column names
     */
    private const val SUBMISSIONS = "submissions"
    private const val PROCESS_CHAINS = "processchains"
    private const val SUBMISSION_ID = "submissionId"
    private const val START_TIME = "startTime"
    private const val END_TIME = "endTime"
    private const val STATUS = "status"
    private const val REQUIRED_CAPABILITIES = "requiredCapabilities"
    private const val RESULTS = "results"
    private const val ERROR_MESSAGE = "errorMessage"
    private const val EXECUTION_STATE = "executionState"
    private const val SERIAL = "serial"
    private const val EXECUTABLES = "executables"
  }

  /**
   * A small cache that reduces the number of database requests for an
   * attribute that never changes
   */
  private val processChainSubmissionIds = CacheBuilder.newBuilder()
      .expireAfterAccess(60, TimeUnit.SECONDS)
      .maximumSize(10000)
      .build<String, String>()

  override suspend fun addSubmission(submission: Submission) {
    val statement = "INSERT INTO $SUBMISSIONS ($ID, $DATA) VALUES ($1, $2)"
    val params = Tuple.of(submission.id, JsonUtils.toJson(submission))
    client.preparedQuery(statement).execute(params).await()
  }

  override suspend fun findSubmissionsRaw(status: Submission.Status?, size: Int,
      offset: Int, order: Int): Collection<JsonObject> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()

    val statement = StringBuilder()
    statement.append("SELECT $DATA FROM $SUBMISSIONS ")

    val params = if (status != null) {
      statement.append("WHERE $DATA->'$STATUS'=$1 ")
      Tuple.of(status.toString())
    } else {
      null
    }

    statement.append("ORDER BY $SERIAL $asc LIMIT $limit OFFSET $offset")

    val rs = if (params != null) {
      client.preparedQuery(statement.toString()).execute(params).await()
    } else {
      client.query(statement.toString()).execute().await()
    }
    return rs.map { it.getJsonObject(0) }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    val statement = "SELECT $DATA FROM $SUBMISSIONS WHERE $ID=$1"
    val params = Tuple.of(submissionId)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs?.firstOrNull()?.let { JsonUtils.fromJson<Submission>(it.getJsonObject(0)) }
  }

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status): Collection<String> {
    val statement = "SELECT $ID FROM $SUBMISSIONS WHERE $DATA->'$STATUS'=$1 " +
        "ORDER BY $SERIAL"
    val params = Tuple.of(status.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.map { it.getString(0) }
  }

  override suspend fun countSubmissions(status: Submission.Status?): Long {
    val statement = StringBuilder()
    statement.append("SELECT COUNT(*) FROM $SUBMISSIONS")

    val params = if (status != null) {
      statement.append(" WHERE $DATA->'$STATUS'=$1")
      Tuple.of(status.toString())
    } else {
      null
    }

    val rs = if (params != null) {
      client.preparedQuery(statement.toString()).execute(params).await()
    } else {
      client.query(statement.toString()).execute().await()
    }
    return rs?.firstOrNull()?.getLong(0) ?: 0L
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    val updateStatement = "UPDATE $SUBMISSIONS SET $DATA=$DATA || $1 " +
        "WHERE $ID = (" +
          "SELECT $ID FROM $SUBMISSIONS WHERE $DATA->'$STATUS'=$2 LIMIT 1 " +
          "FOR UPDATE SKIP LOCKED" + // skip rows being updated concurrently
        ") RETURNING $DATA"
    val newObj = json {
      obj(
          STATUS to newStatus.toString()
      )
    }
    val updateParams = Tuple.of(newObj, currentStatus.toString())
    val rs = client.preparedQuery(updateStatement).execute(updateParams).await()
    return rs?.firstOrNull()?.let { JsonUtils.fromJson<Submission>(it.getJsonObject(0))
        .copy(status = currentStatus) }
  }

  override suspend fun setSubmissionStartTime(submissionId: String, startTime: Instant) {
    val newObj = json {
      obj(
          START_TIME to startTime
      )
    }
    updateProperties(SUBMISSIONS, submissionId, newObj)
  }

  override suspend fun setSubmissionEndTime(submissionId: String, endTime: Instant) {
    val newObj = json {
      obj(
          END_TIME to endTime
      )
    }
    updateProperties(SUBMISSIONS, submissionId, newObj)
  }

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    val newObj = json {
      obj(
          STATUS to status.toString()
      )
    }
    updateProperties(SUBMISSIONS, submissionId, newObj)
  }

  override suspend fun getSubmissionStatus(submissionId: String): Submission.Status {
    val statement = "SELECT $DATA->'$STATUS' FROM $SUBMISSIONS WHERE $ID=$1"
    val params = Tuple.of(submissionId)
    val rs = client.preparedQuery(statement).execute(params).await()?.firstOrNull()
        ?: throw NoSuchElementException("There is no submission with ID `$submissionId'")
    return Submission.Status.valueOf(rs.getString(0))
  }

  private suspend fun <T> getSubmissionColumn(submissionId: String,
      column: String, block: (Row) -> T): T {
    val statement = "SELECT $column FROM $SUBMISSIONS WHERE $ID=$1"
    val params = Tuple.of(submissionId)
    val r = client.preparedQuery(statement).execute(params).await()?.firstOrNull()
        ?: throw NoSuchElementException("There is no submission with ID `$submissionId'")
    return block(r)
  }

  override suspend fun setSubmissionResults(submissionId: String,
      results: Map<String, List<Any>>?) {
    updateColumn(SUBMISSIONS, submissionId, RESULTS, results?.let { JsonUtils.toJson(it) })
  }

  override suspend fun getSubmissionResults(submissionId: String): Map<String, List<Any>>? =
      getSubmissionColumn(submissionId, RESULTS) { r ->
        r.getJsonObject(0)?.let { JsonUtils.fromJson<Map<String, List<Any>>>(it) } }

  override suspend fun setSubmissionErrorMessage(submissionId: String,
      errorMessage: String?) {
    updateColumn(SUBMISSIONS, submissionId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getSubmissionErrorMessage(submissionId: String): String? =
      getSubmissionColumn(submissionId, ERROR_MESSAGE) { it.getString(0) }

  override suspend fun setSubmissionExecutionState(submissionId: String, state: JsonObject?) {
    updateColumn(SUBMISSIONS, submissionId, EXECUTION_STATE, state?.encode())
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? =
      getSubmissionColumn(submissionId, EXECUTION_STATE) { rs ->
        rs.getString(0)?.let { JsonObject(it) } }

  override suspend fun deleteSubmissionsFinishedBefore(timestamp: Instant): Collection<String> {
    return withConnection { connection ->
      // find IDs of submissions whose end time is before the given timestamp
      val statement1 = "SELECT $ID FROM $SUBMISSIONS WHERE $DATA->'$END_TIME' < $1"
      val params1 = Tuple.of(timestamp.toString())
      val rs1 = connection.preparedQuery(statement1).execute(params1).await()
      val submissionIDs1 = rs1.map { it.getString(0) }

      // find IDs of finished submissions that do not have an endTime but
      // whose ID was created before the given timestamp (this will also
      // include submissions without a startTime)
      val statement2 = "SELECT $ID FROM $SUBMISSIONS WHERE $DATA->'$END_TIME' IS NULL " +
          "AND $DATA->'$STATUS'!=$1 AND $DATA->'$STATUS'!=$2"
      val params2 = Tuple.of(Submission.Status.ACCEPTED.toString(),
        Submission.Status.RUNNING.toString())
      val rs2 = connection.preparedQuery(statement2).execute(params2).await()
      val submissionIDs2 = rs2.map { it.getString(0) }
        .filter { Instant.ofEpochMilli(UniqueID.toMillis(it)).isBefore(timestamp) }

      val submissionIDs = submissionIDs1 + submissionIDs2

      // delete 1000 submissions at once
      for (chunk in submissionIDs.chunked(1000)) {
        val deleteParams = Tuple.of(submissionIDs.toTypedArray())

        // delete process chains first
        val statement3 = "DELETE FROM $PROCESS_CHAINS " +
            "WHERE $SUBMISSION_ID=ANY($1)"
        connection.preparedQuery(statement3).execute(deleteParams).await()

        // then delete submissions
        val statement4 = "DELETE FROM $SUBMISSIONS WHERE $ID=ANY($1)"
        connection.preparedQuery(statement4).execute(deleteParams).await()
      }

      submissionIDs
    }
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    withConnection { connection ->
      val existsStatement = "SELECT 1 FROM $SUBMISSIONS WHERE $ID=$1"
      val existsParam = Tuple.of(submissionId)
      if (connection.preparedQuery(existsStatement).execute(existsParam).await()
          ?.firstOrNull() == null) {
        throw NoSuchElementException("There is no submission with ID `$submissionId'")
      }

      val insertStatement = "INSERT INTO $PROCESS_CHAINS ($ID, $SUBMISSION_ID, $STATUS, $DATA) " +
          "VALUES ($1, $2, $3, $4)"
      val insertParams = processChains.map {
        Tuple.of(it.id, submissionId, status.toString(), JsonUtils.toJson(it))
      }
      connection.preparedQuery(insertStatement).executeBatch(insertParams).await()
    }
  }

  override suspend fun findProcessChains(submissionId: String?, status: ProcessChainStatus?,
      size: Int, offset: Int, order: Int, excludeExecutables: Boolean):
      Collection<Pair<ProcessChain, String>> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()

    val statement = StringBuilder()
    if (excludeExecutables) {
      statement.append("SELECT $DATA #- '{$EXECUTABLES}', $SUBMISSION_ID FROM $PROCESS_CHAINS ")
    } else {
      statement.append("SELECT $DATA, $SUBMISSION_ID FROM $PROCESS_CHAINS ")
    }

    val params = if (submissionId != null && status != null) {
      statement.append("WHERE $SUBMISSION_ID=$1 AND $STATUS=$2 ")
      Tuple.of(submissionId, status.toString())
    } else if (submissionId != null) {
      statement.append("WHERE $SUBMISSION_ID=$1 ")
      Tuple.of(submissionId)
    } else if (status != null) {
      statement.append("WHERE $STATUS=$1 ")
      Tuple.of(status.toString())
    } else {
      null
    }

    statement.append("ORDER BY $SERIAL $asc LIMIT $limit OFFSET $offset")

    val rs = if (params != null) {
      client.preparedQuery(statement.toString()).execute(params).await()
    } else {
      client.query(statement.toString()).execute().await()
    }

    return rs.map { Pair(JsonUtils.fromJson(it.getJsonObject(0)), it.getString(1)) }
  }

  override suspend fun findProcessChainIdsByStatus(
      status: ProcessChainStatus): List<String> {
    val statement = "SELECT $ID FROM $PROCESS_CHAINS " +
        "WHERE $STATUS=$1 ORDER BY $SERIAL"
    val params = Tuple.of(status.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.map { it.getString(0) }
  }

  override suspend fun findProcessChainIdsBySubmissionIdAndStatus(
      submissionId: String, status: ProcessChainStatus): List<String> {
    val statement = "SELECT $ID FROM $PROCESS_CHAINS " +
        "WHERE $SUBMISSION_ID=$1 AND $STATUS=$2 ORDER BY $SERIAL"
    val params = Tuple.of(submissionId, status.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.map { it.getString(0) }
  }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String):
      Map<String, ProcessChainStatus> {
    val statement = "SELECT $ID, $STATUS FROM $PROCESS_CHAINS " +
        "WHERE $SUBMISSION_ID=$1 ORDER BY $SERIAL"
    val params = Tuple.of(submissionId)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.associate { Pair(it.getString(0), ProcessChainStatus.valueOf(it.getString(1))) }
  }

  override suspend fun findProcessChainRequiredCapabilities(
      status: ProcessChainStatus): List<Collection<String>> {
    val statement = "SELECT DISTINCT $DATA->'$REQUIRED_CAPABILITIES' " +
        "FROM $PROCESS_CHAINS WHERE $STATUS=$1"
    val params = Tuple.of(status.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.map { row -> row.getJsonArray(0).map { it.toString() } }
  }

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    val statement = "SELECT $DATA FROM $PROCESS_CHAINS WHERE $ID=$1"
    val params = Tuple.of(processChainId)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs?.firstOrNull()?.let { JsonUtils.fromJson<ProcessChain>(it.getJsonObject(0)) }
  }

  override suspend fun countProcessChains(submissionId: String?,
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?): Long {
    val statement = StringBuilder()
    statement.append("SELECT COUNT(*) FROM $PROCESS_CHAINS")

    val conditions = mutableListOf<String>()
    val params = Tuple.tuple()

    var pos = 1
    if (submissionId != null) {
      conditions.add("$SUBMISSION_ID=$${pos++}")
      params.addString(submissionId)
    }
    if (status != null) {
      conditions.add("$STATUS=$${pos++}")
      params.addString(status.toString())
    }
    if (requiredCapabilities != null) {
      conditions.add("$DATA->'$REQUIRED_CAPABILITIES'=$${pos}")
      params.addValue(JsonArray(requiredCapabilities.toList()))
    }

    val rs = if (conditions.isNotEmpty()) {
      statement.append(" WHERE ")
      statement.append(conditions.joinToString(" AND "))
      client.preparedQuery(statement.toString()).execute(params).await()
    } else {
      client.query(statement.toString()).execute().await()
    }

    return rs?.firstOrNull()?.getLong(0) ?: 0L
  }

  override suspend fun countProcessChainsPerStatus(submissionId: String?):
      Map<ProcessChainStatus, Long> {
    val statement = StringBuilder()
    statement.append("SELECT $STATUS, COUNT(*) FROM $PROCESS_CHAINS")

    val params = Tuple.tuple()
    if (submissionId != null) {
      statement.append(" WHERE $SUBMISSION_ID=$1")
      params.addString(submissionId)
    }
    statement.append(" GROUP BY $STATUS")

    val rs = client.preparedQuery(statement.toString()).execute(params).await()
    return rs.associateBy({ ProcessChainStatus.valueOf(it.getString(0)) },
        { it.getLong(1) })
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?): ProcessChain? {
    val (selectStatement, params) = if (requiredCapabilities == null) {
      "SELECT $ID FROM $PROCESS_CHAINS WHERE $STATUS=$2" to json {
        Tuple.of(newStatus.toString(), currentStatus.toString())
      }
    } else {
      "SELECT $ID FROM $PROCESS_CHAINS WHERE $STATUS=$2 " +
          "AND $DATA->'$REQUIRED_CAPABILITIES'=$3" to json {
        Tuple.of(newStatus.toString(), currentStatus.toString(),
            JsonArray(requiredCapabilities.toList()))
      }
    }

    val updateStatement = "UPDATE $PROCESS_CHAINS SET $STATUS=$1 " +
        "WHERE $ID = (" +
          "$selectStatement " +
          "ORDER BY $SERIAL LIMIT 1 " +
          "FOR UPDATE SKIP LOCKED" + // skip rows being updated concurrently
        ") RETURNING $DATA"
    val rs = client.preparedQuery(updateStatement).execute(params).await()
    return rs?.firstOrNull()?.let { JsonUtils.fromJson<ProcessChain>(it.getJsonObject(0)) }
  }

  override suspend fun existsProcessChain(currentStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>?): Boolean {
    val (statement, params) = if (requiredCapabilities == null) {
      "SELECT 1 FROM $PROCESS_CHAINS WHERE $STATUS=$1 LIMIT 1" to json {
        Tuple.of(currentStatus.toString())
      }
    } else {
      "SELECT 1 FROM $PROCESS_CHAINS WHERE $STATUS=$1 " +
          "AND $DATA->'$REQUIRED_CAPABILITIES'=$2 LIMIT 1" to json {
        Tuple.of(currentStatus.toString(), JsonArray(requiredCapabilities.toList()))
      }
    }

    return client.preparedQuery(statement).execute(params).await()?.firstOrNull() != null
  }

  private suspend fun <T> getProcessChainColumn(processChainId: String,
      column: String, block: (Row) -> T): T {
    val statement = "SELECT $column FROM $PROCESS_CHAINS WHERE $ID=$1"
    val params = Tuple.of(processChainId)
    val r = client.preparedQuery(statement).execute(params).await().firstOrNull()
        ?: throw NoSuchElementException("There is no process chain with ID `$processChainId'")
    return block(r)
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    updateColumn(PROCESS_CHAINS, processChainId, START_TIME,
        JsonUtils.writeValueAsString(startTime))
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainColumn(processChainId, START_TIME) { it.getString(0)?.let { s ->
        JsonUtils.mapper.readValue(s, Instant::class.java) } }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateColumn(PROCESS_CHAINS, processChainId, END_TIME,
        JsonUtils.writeValueAsString(endTime))
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainColumn(processChainId, END_TIME) { it.getString(0)?.let { s ->
        JsonUtils.mapper.readValue(s, Instant::class.java) } }

  override suspend fun getProcessChainSubmissionId(processChainId: String): String {
    return processChainSubmissionIds.getIfPresent(processChainId) ?: run {
      val sid = getProcessChainColumn(processChainId, SUBMISSION_ID) { it.getString(0) }
      processChainSubmissionIds.put(processChainId, sid)
      sid
    }
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateColumn(PROCESS_CHAINS, processChainId, STATUS, status.toString())
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    updateColumn(PROCESS_CHAINS, processChainId, STATUS,
        currentStatus.toString(), newStatus.toString())
  }

  override suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    val updateStatement = "UPDATE $PROCESS_CHAINS SET $STATUS=$1 WHERE " +
        "$SUBMISSION_ID=$2 AND $STATUS=$3"
    val updateParams = Tuple.of(newStatus.toString(), submissionId, currentStatus.toString())
    client.preparedQuery(updateStatement).execute(updateParams).await()
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus =
      getProcessChainColumn(processChainId, STATUS) { r ->
        r.getString(0).let { ProcessChainStatus.valueOf(it) } }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    updateColumn(PROCESS_CHAINS, processChainId, RESULTS, results?.let{ JsonUtils.toJson(it) })
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? =
      getProcessChainColumn(processChainId, RESULTS) { r ->
        r.getJsonObject(0)?.let { JsonUtils.fromJson<Map<String, List<Any>>>(it) } }

  override suspend fun getProcessChainStatusAndResultsIfFinished(processChainIds: Collection<String>):
      Map<String, Pair<ProcessChainStatus, Map<String, List<Any>>?>> {
    val statement = "SELECT $ID, $STATUS, $RESULTS FROM $PROCESS_CHAINS " +
        "WHERE $STATUS!=$1 AND $STATUS!=$2 AND $ID=ANY($3)"
    val params = Tuple.of(
        ProcessChainStatus.REGISTERED,
        ProcessChainStatus.RUNNING,
        processChainIds.toTypedArray()
    )
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.associateBy({ it.getString(0) }, { row ->
      val status = ProcessChainStatus.valueOf(row.getString(1))
      val results = row.getJsonObject(2)?.let { JsonUtils.fromJson<Map<String, List<Any>>>(it) }
      status to results
    })
  }

  override suspend fun setProcessChainErrorMessage(processChainId: String,
      errorMessage: String?) {
    updateColumn(PROCESS_CHAINS, processChainId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainColumn(processChainId, ERROR_MESSAGE) { it.getString(0) }
}
