package db

import com.google.common.cache.CacheBuilder
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.sql.SQLConnection
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.core.shareddata.getLockAwait
import io.vertx.kotlin.ext.sql.batchWithParamsAwait
import io.vertx.kotlin.ext.sql.executeAwait
import io.vertx.kotlin.ext.sql.queryAwait
import io.vertx.kotlin.ext.sql.querySingleAwait
import io.vertx.kotlin.ext.sql.querySingleWithParamsAwait
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import io.vertx.kotlin.ext.sql.updateWithParamsAwait
import model.Submission
import model.processchain.ProcessChain
import java.lang.StringBuilder
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

    /**
     * Identifier of a PostgreSQL advisory lock used to make atomic operations
     */
    private const val ADVISORY_LOCK_ID =
        ('J'.toLong() shl 56) +
        ('o'.toLong() shl 48) +
        ('b'.toLong() shl 40) +
        ('M'.toLong() shl 32) +
        ('a'.toLong() shl 24) +
        ('n'.toLong() shl 16) +
        ('a'.toLong() shl 8) +
        ('g'.toLong())

    /**
     * Name of a cluster-wide lock used to make atomic operations
     */
    private const val LOCK = "PostgreSQLSubmissionRegistry.Lock"
  }

  /**
   * A small cache that reduces the number of database requests for an
   * attribute that never changes
   */
  private val processChainSubmissionIds = CacheBuilder.newBuilder()
      .expireAfterAccess(60, TimeUnit.SECONDS)
      .maximumSize(10000)
      .build<String, String>()

  /**
   * Atomically executes the given block. We are using two locks: a cluster-wide
   * one to protect against concurrent access from within the same cluster and
   * a PostgreSQL advisory lock to protect against concurrent access from other
   * processes outside the cluster. We need two locks because PostgreSQL
   * advisory locks are re-entrant (within the same process).
   * @param block the block to execute
   * @return the block's result
   */
  private suspend fun <T> withLocks(block: suspend (SQLConnection) -> T): T {
    return withConnection { connection ->
      val sharedData = vertx.sharedData()
      val lock = sharedData.getLockAwait(LOCK)
      try {
        connection.executeAwait("SELECT pg_advisory_lock($ADVISORY_LOCK_ID)")
        try {
          block(connection)
        } finally {
          connection.executeAwait("SELECT pg_advisory_unlock($ADVISORY_LOCK_ID)")
        }
      } finally {
        lock.release()
      }
    }
  }

  override suspend fun addSubmission(submission: Submission) {
    withConnection { connection ->
      val statement = "INSERT INTO $SUBMISSIONS ($ID, $DATA) VALUES (?, ?::jsonb)"
      val params = json {
        array(
            submission.id,
            JsonUtils.writeValueAsString(submission)
        )
      }
      connection.updateWithParamsAwait(statement, params)
    }
  }

  override suspend fun findSubmissions(status: Submission.Status?, size: Int,
      offset: Int, order: Int): Collection<Submission> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()

    val statement = StringBuilder()
    statement.append("SELECT $DATA FROM $SUBMISSIONS ")

    val params = if (status != null) {
      statement.append("WHERE $DATA->'$STATUS'=?::jsonb ")
      json {
        array(
            "\"$status\""
        )
      }
    } else {
      null
    }

    statement.append("ORDER BY $SERIAL $asc LIMIT $limit OFFSET $offset")

    return withConnection { connection ->
      val rs = if (params != null) {
        connection.queryWithParamsAwait(statement.toString(), params)
      } else {
        connection.queryAwait(statement.toString())
      }
      rs.results.map { JsonUtils.readValue(it.getString(0)) }
    }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $SUBMISSIONS WHERE $ID=?"
      val params = json {
        array(
            submissionId
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { JsonUtils.readValue<Submission>(it.getString(0)) }
    }
  }

  override suspend fun findSubmissionIdsByStatus(status: Submission.Status): Collection<String> {
    return withConnection { connection ->
      val statement = "SELECT $ID FROM $SUBMISSIONS WHERE $DATA->'$STATUS'=?::jsonb " +
          "ORDER BY $SERIAL"
      val params = json {
        array(
            "\"$status\""
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { it.getString(0) }
    }
  }

  override suspend fun countSubmissions(status: Submission.Status?): Long {
    return withConnection { connection ->
      val statement = StringBuilder()
      statement.append("SELECT COUNT(*) FROM $SUBMISSIONS")

      val params = if (status != null) {
        statement.append(" WHERE $DATA->'$STATUS'=?::jsonb")
        json {
          array(
              "\"$status\""
          )
        }
      } else {
        null
      }

      val rs = if (params != null) {
        connection.querySingleWithParamsAwait(statement.toString(), params)
      } else {
        connection.querySingleAwait(statement.toString())
      }
      rs?.getLong(0) ?: 0L
    }
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    return withLocks { connection ->
      val statement = "SELECT $DATA FROM $SUBMISSIONS WHERE " +
          "$DATA->'$STATUS'=?::jsonb LIMIT 1"
      val params = json {
        array(
            "\"$currentStatus\""
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { arr ->
        JsonUtils.readValue<Submission>(arr.getString(0)).also {
          val newObj = json {
            obj(
                STATUS to newStatus.toString()
            )
          }
          updateProperties(SUBMISSIONS, it.id, newObj, connection)
        }
      }
    }
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
    return withConnection { connection ->
      val statement = "SELECT $DATA->'$STATUS' FROM $SUBMISSIONS WHERE $ID=?"
      val params = json {
        array(
            submissionId
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params) ?: throw NoSuchElementException(
          "There is no submission with ID `$submissionId'")
      Submission.Status.valueOf(JsonUtils.readValue(rs.getString(0)))
    }
  }

  private suspend fun <T> getSubmissionColumn(submissionId: String,
      column: String, block: (JsonArray) -> T): T {
    return withConnection { connection ->
      val statement = "SELECT $column FROM $SUBMISSIONS WHERE $ID=?"
      val params = json {
        array(
            submissionId
        )
      }
      val r = connection.querySingleWithParamsAwait(statement, params) ?: throw NoSuchElementException(
          "There is no submission with ID `$submissionId'")
      block(r)
    }
  }

  override suspend fun setSubmissionResults(submissionId: String,
      results: Map<String, List<Any>>?) {
    updateColumn(SUBMISSIONS, submissionId, RESULTS,
        JsonUtils.writeValueAsString(results), true)
  }

  override suspend fun getSubmissionResults(submissionId: String): Map<String, List<Any>>? =
      getSubmissionColumn(submissionId, RESULTS) { r ->
        r.getString(0)?.let { JsonUtils.readValue<Map<String, List<Any>>>(it) } }

  override suspend fun setSubmissionErrorMessage(submissionId: String,
      errorMessage: String?) {
    updateColumn(SUBMISSIONS, submissionId, ERROR_MESSAGE, errorMessage, false)
  }

  override suspend fun getSubmissionErrorMessage(submissionId: String): String? =
      getSubmissionColumn(submissionId, ERROR_MESSAGE) { it.getString(0) }

  override suspend fun setSubmissionExecutionState(submissionId: String, state: JsonObject?) {
    updateColumn(SUBMISSIONS, submissionId, EXECUTION_STATE, state?.encode(), false)
  }

  override suspend fun getSubmissionExecutionState(submissionId: String): JsonObject? =
      getSubmissionColumn(submissionId, EXECUTION_STATE) { rs ->
        rs.getString(0)?.let { JsonObject(it) } }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    withLocks { connection ->
      val existsStatement = "SELECT 1 FROM $SUBMISSIONS WHERE $ID=?"
      val existsParam = json {
        array(
            submissionId
        )
      }
      if (connection.querySingleWithParamsAwait(existsStatement, existsParam) == null) {
        throw NoSuchElementException("There is no submission with ID `$submissionId'")
      }

      val insertStatement = "INSERT INTO $PROCESS_CHAINS ($ID, $SUBMISSION_ID, $STATUS, $DATA) " +
          "VALUES (?, ?, ?, ?::jsonb)"
      val insertParams = processChains.map {
        json {
          array(
              it.id,
              submissionId,
              status.toString(),
              JsonUtils.writeValueAsString(it)
          )
        }
      }
      connection.batchWithParamsAwait(insertStatement, insertParams)
    }
  }

  override suspend fun findProcessChains(submissionId: String?, status: ProcessChainStatus?,
      size: Int, offset: Int, order: Int): Collection<Pair<ProcessChain, String>> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()

    return withConnection { connection ->
      val statement = StringBuilder()
      statement.append("SELECT $DATA, $SUBMISSION_ID FROM $PROCESS_CHAINS ")

      val params = if (submissionId != null && status != null) {
        statement.append("WHERE $SUBMISSION_ID=? AND $STATUS=? ")
        json {
          array(
              submissionId,
              status.toString()
          )
        }
      } else if (submissionId != null) {
        statement.append("WHERE $SUBMISSION_ID=? ")
        json {
          array(
              submissionId
          )
        }
      } else if (status != null) {
        statement.append("WHERE $STATUS=? ")
        json {
          array(
              status.toString()
          )
        }
      } else {
        null
      }

      statement.append("ORDER BY $SERIAL $asc LIMIT $limit OFFSET $offset")

      val rs = if (params != null) {
        connection.queryWithParamsAwait(statement.toString(), params)
      } else {
        connection.queryAwait(statement.toString())
      }

      rs.results.map { Pair(JsonUtils.readValue(it.getString(0)), it.getString(1)) }
    }
  }

  override suspend fun findProcessChainIdsByStatus(
      status: ProcessChainStatus): List<String> {
    return withConnection { connection ->
      val statement = "SELECT $ID FROM $PROCESS_CHAINS " +
          "WHERE $STATUS=? ORDER BY $SERIAL"
      val params = json {
        array(
            status.toString()
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { it.getString(0) }
    }
  }

  override suspend fun findProcessChainIdsBySubmissionIdAndStatus(
      submissionId: String, status: ProcessChainStatus): List<String> {
    return withConnection { connection ->
      val statement = "SELECT $ID FROM $PROCESS_CHAINS " +
          "WHERE $SUBMISSION_ID=? AND $STATUS=? ORDER BY $SERIAL"
      val params = json {
        array(
            submissionId,
            status.toString()
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { it.getString(0) }
    }
  }

  override suspend fun findProcessChainStatusesBySubmissionId(submissionId: String):
      Map<String, ProcessChainStatus> {
    return withConnection { connection ->
      val statement = "SELECT $ID, $STATUS FROM $PROCESS_CHAINS " +
          "WHERE $SUBMISSION_ID=? ORDER BY $SERIAL"
      val params = json {
        array(
            submissionId
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.associate { Pair(it.getString(0), ProcessChainStatus.valueOf(it.getString(1))) }
    }
  }

  override suspend fun findProcessChainRequiredCapabilities(
      status: ProcessChainStatus): List<Collection<String>> {
    return withConnection { connection ->
      val statement = "SELECT DISTINCT $DATA->'$REQUIRED_CAPABILITIES' " +
          "FROM $PROCESS_CHAINS WHERE $STATUS=?"
      val params = json {
        array(
            status.toString()
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { JsonUtils.readValue<List<String>>(it.getString(0)) }
    }
  }

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $PROCESS_CHAINS WHERE $ID=?"
      val params = json {
        array(
            processChainId
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { JsonUtils.readValue<ProcessChain>(it.getString(0)) }
    }
  }

  override suspend fun countProcessChains(submissionId: String?,
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?): Long {
    return withConnection { connection ->
      val statement = StringBuilder()
      statement.append("SELECT COUNT(*) FROM $PROCESS_CHAINS")

      val conditions = mutableListOf<String>()
      val params = JsonArray()

      if (submissionId != null) {
        conditions.add("$SUBMISSION_ID=?")
        params.add(submissionId)
      }
      if (status != null) {
        conditions.add("$STATUS=?")
        params.add(status.toString())
      }
      if (requiredCapabilities != null) {
        conditions.add("$DATA->'$REQUIRED_CAPABILITIES'=?::jsonb")
        params.add(JsonUtils.writeValueAsString(requiredCapabilities))
      }

      val rs = if (conditions.isNotEmpty()) {
        statement.append(" WHERE ")
        statement.append(conditions.joinToString(" AND "))
        connection.querySingleWithParamsAwait(statement.toString(), params)
      } else {
        connection.querySingleAwait(statement.toString())
      }

      rs?.getLong(0) ?: 0L
    }
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?): ProcessChain? {
    return withLocks { connection ->
      val (statement, params) = if (requiredCapabilities == null) {
        "SELECT $DATA FROM $PROCESS_CHAINS WHERE $STATUS=? " +
            "ORDER BY $SERIAL LIMIT 1" to json {
          array(
              currentStatus.toString()
          )
        }
      } else {
        "SELECT $DATA FROM $PROCESS_CHAINS WHERE $STATUS=? " +
            "AND $DATA->'$REQUIRED_CAPABILITIES'=?::jsonb " +
            "ORDER BY $SERIAL LIMIT 1" to json {
          array(
              currentStatus.toString(),
              JsonUtils.writeValueAsString(requiredCapabilities)
          )
        }
      }

      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { arr ->
        JsonUtils.readValue<ProcessChain>(arr.getString(0)).also {
          updateColumn(PROCESS_CHAINS, it.id, STATUS, newStatus.toString(),
              false, connection)
        }
      }
    }
  }

  override suspend fun existsProcessChain(currentStatus: ProcessChainStatus,
      requiredCapabilities: Collection<String>?): Boolean {
    return withLocks { connection ->
      val (statement, params) = if (requiredCapabilities == null) {
        "SELECT 1 FROM $PROCESS_CHAINS WHERE $STATUS=? LIMIT 1" to json {
          array(
              currentStatus.toString()
          )
        }
      } else {
        "SELECT 1 FROM $PROCESS_CHAINS WHERE $STATUS=? " +
            "AND $DATA->'$REQUIRED_CAPABILITIES'=?::jsonb LIMIT 1" to json {
          array(
              currentStatus.toString(),
              JsonUtils.writeValueAsString(requiredCapabilities)
          )
        }
      }

      connection.querySingleWithParamsAwait(statement, params) != null
    }
  }

  private suspend fun <T> getProcessChainColumn(processChainId: String,
      column: String, block: (JsonArray) -> T): T {
    return withConnection { connection ->
      val statement = "SELECT $column FROM $PROCESS_CHAINS WHERE $ID=?"
      val params = json {
        array(
            processChainId
        )
      }
      val r = connection.querySingleWithParamsAwait(statement, params) ?: throw NoSuchElementException(
          "There is no process chain with ID `$processChainId'")
      block(r)
    }
  }

  override suspend fun setProcessChainStartTime(processChainId: String, startTime: Instant?) {
    updateColumn(PROCESS_CHAINS, processChainId, START_TIME,
        JsonUtils.writeValueAsString(startTime), false)
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainColumn(processChainId, START_TIME) { it.getString(0)?.let { s ->
        JsonUtils.mapper.readValue(s, Instant::class.java) } }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateColumn(PROCESS_CHAINS, processChainId, END_TIME,
        JsonUtils.writeValueAsString(endTime), false)
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
    updateColumn(PROCESS_CHAINS, processChainId, STATUS, status.toString(), false)
  }

  override suspend fun setProcessChainStatus(processChainId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    updateColumn(PROCESS_CHAINS, processChainId, STATUS,
        currentStatus.toString(), newStatus.toString(), false)
  }

  override suspend fun setAllProcessChainsStatus(submissionId: String,
      currentStatus: ProcessChainStatus, newStatus: ProcessChainStatus) {
    withConnection { connection ->
      val updateStatement = "UPDATE $PROCESS_CHAINS SET $STATUS=? WHERE " +
          "$SUBMISSION_ID=? AND $STATUS=?"
      val updateParams = json {
        array(
            newStatus.toString(),
            submissionId,
            currentStatus.toString()
        )
      }
      connection.updateWithParamsAwait(updateStatement, updateParams)
    }
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus =
      getProcessChainColumn(processChainId, STATUS) { r ->
        r.getString(0).let { ProcessChainStatus.valueOf(it) } }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    updateColumn(PROCESS_CHAINS, processChainId, RESULTS,
        JsonUtils.writeValueAsString(results), true)
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? =
      getProcessChainColumn(processChainId, RESULTS) { r ->
        r.getString(0)?.let { JsonUtils.readValue<Map<String, List<Any>>>(it) } }

  override suspend fun setProcessChainErrorMessage(processChainId: String,
      errorMessage: String?) {
    updateColumn(PROCESS_CHAINS, processChainId, ERROR_MESSAGE, errorMessage, false)
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainColumn(processChainId, ERROR_MESSAGE) { it.getString(0) }
}
