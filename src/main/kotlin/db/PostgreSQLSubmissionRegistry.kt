package db

import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.cache.CacheBuilder
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.await
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.Tuple
import model.Submission
import model.processchain.ProcessChain
import search.DateTerm
import search.DateTimeRangeTerm
import search.DateTimeTerm
import search.Locator
import search.Operator
import search.Query
import search.SearchResult
import search.StringTerm
import search.Term
import search.Type
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter.ISO_INSTANT
import java.time.temporal.ChronoUnit
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
    private const val PRIORITY = "priority"
    private const val EXECUTABLES = "executables"
    private const val WORKFLOW = "workflow"
    private const val NAME = "name"
    private const val SOURCE = "source"
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
    val obj = JsonUtils.toJson(submission)

    // Make sure there's always a priority even if it's 0 (we configured Jackson
    // to not serialize 0's by default). Otherwise, we can't sort correctly.
    obj.getJsonObject(WORKFLOW)?.put(PRIORITY, submission.workflow.priority)

    val params = Tuple.of(submission.id, obj)
    client.preparedQuery(statement).execute(params).await()
  }

  override suspend fun findSubmissionsRaw(status: Submission.Status?, size: Int,
      offset: Int, order: Int, excludeWorkflows: Boolean,
      excludeSources: Boolean): Collection<JsonObject> {
    val asc = if (order >= 0) "ASC" else "DESC"
    val limit = if (size < 0) "ALL" else size.toString()

    val excludesList = mutableListOf<String>()
    if (excludeWorkflows) {
      excludesList.add(WORKFLOW)
    }
    if (excludeSources) {
      excludesList.add(SOURCE)
    }
    val excludes = excludesList.joinToString(" ", transform = {" #- '{$it}'"})

    val statement = StringBuilder()
    statement.append("SELECT $DATA$excludes FROM $SUBMISSIONS ")

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
    return rs.map { row ->
      val s = row.getJsonObject(0)

      // remove priority that we only added for sorting (see [addSubmission])
      if (s.getJsonObject(WORKFLOW)?.getInteger(PRIORITY) == 0) {
        s.getJsonObject(WORKFLOW)?.remove(PRIORITY)
      }

      s
    }
  }

  override suspend fun findSubmissionById(submissionId: String): Submission? {
    val statement = "SELECT $DATA::varchar FROM $SUBMISSIONS WHERE $ID=$1"
    val params = Tuple.of(submissionId)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs?.firstOrNull()?.let { JsonUtils.mapper.readValue(it.getString(0)) }
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
          "SELECT $ID FROM $SUBMISSIONS WHERE $DATA->'$STATUS'=$2 " +
          "ORDER BY $DATA->'$WORKFLOW'->'$PRIORITY' DESC, $SERIAL ASC LIMIT 1 " +
          "FOR UPDATE SKIP LOCKED" + // skip rows being updated concurrently
        ") RETURNING $DATA::varchar"
    val newObj = json {
      obj(
          STATUS to newStatus.toString()
      )
    }
    val updateParams = Tuple.of(newObj, currentStatus.toString())
    val rs = client.preparedQuery(updateStatement).execute(updateParams).await()
    return rs?.firstOrNull()?.let { JsonUtils.mapper.readValue<Submission>(it.getString(0))
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

  override suspend fun setSubmissionPriority(submissionId: String, priority: Int): Boolean {
    val newObj = jsonObjectOf(PRIORITY to priority)
    val updateStatement = "UPDATE $SUBMISSIONS SET $DATA=$DATA || $1 " +
        "WHERE $ID=$2 AND ($DATA->'$PRIORITY' IS NULL OR $DATA->'$PRIORITY'!=$3) AND " +
        "($DATA->'$STATUS'=$4 OR $DATA->'$STATUS'=$5) RETURNING 1"
    val updateParams = Tuple.of(newObj, submissionId, priority,
        Submission.Status.ACCEPTED.toString(), Submission.Status.RUNNING.toString())
    val result = client.preparedQuery(updateStatement).execute(updateParams).await()
    return result.size() > 0
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
      getSubmissionColumn(submissionId, "$RESULTS::varchar") { r ->
        r.getString(0)?.let { JsonUtils.mapper.readValue(it) } }

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
        val obj = JsonUtils.toJson(it)

        // for correct sorting, make sure there's always a priority even if it's 0
        // (we configured Jackson to not serialize 0's)
        obj.put(PRIORITY, it.priority)

        Tuple.of(it.id, submissionId, status.toString(), obj)
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
      statement.append("SELECT ($DATA #- '{$EXECUTABLES}')::varchar, $SUBMISSION_ID FROM $PROCESS_CHAINS ")
    } else {
      statement.append("SELECT $DATA::varchar, $SUBMISSION_ID FROM $PROCESS_CHAINS ")
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

    return rs.map { Pair(JsonUtils.mapper.readValue(it.getString(0)), it.getString(1)) }
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
      status: ProcessChainStatus): List<Pair<Collection<String>, IntRange>> {
    val statement = "SELECT $DATA->'$REQUIRED_CAPABILITIES'," +
        "MIN(($DATA->>'$PRIORITY')::int),MAX(($DATA->>'$PRIORITY')::int) " +
        "FROM $PROCESS_CHAINS WHERE $STATUS=$1 " +
        "GROUP BY $DATA->'$REQUIRED_CAPABILITIES'"
    val params = Tuple.of(status.toString())
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.map { row ->
      val rcs = row.getJsonArray(0).map { it.toString() }
      val min = row.getInteger(1)
      val max = row.getInteger(2)
      rcs to min..max
    }
  }

  override suspend fun findProcessChainById(processChainId: String): ProcessChain? {
    val statement = "SELECT $DATA::varchar FROM $PROCESS_CHAINS WHERE $ID=$1"
    val params = Tuple.of(processChainId)
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs?.firstOrNull()?.let { JsonUtils.mapper.readValue(it.getString(0)) }
  }

  override suspend fun countProcessChains(submissionId: String?,
      status: ProcessChainStatus?, requiredCapabilities: Collection<String>?,
      minPriority: Int?): Long {
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
      conditions.add("$DATA->'$REQUIRED_CAPABILITIES'=$${pos++}")
      params.addValue(JsonArray(requiredCapabilities.toList()))
    }
    if (minPriority != null) {
      conditions.add("$DATA->'$PRIORITY'>=$${pos}")
      params.addValue(minPriority)
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
      newStatus: ProcessChainStatus, requiredCapabilities: Collection<String>?,
      minPriority: Int?): ProcessChain? {
    val conditions = mutableListOf<String>()
    val params = Tuple.of(newStatus.toString())

    var pos = 2
    conditions.add("$STATUS=$${pos++}")
    params.addString(currentStatus.toString())
    if (requiredCapabilities != null) {
      conditions.add("$DATA->'$REQUIRED_CAPABILITIES'=$${pos++}")
      params.addValue(JsonArray(requiredCapabilities.toList()))
    }
    if (minPriority != null) {
      conditions.add("$DATA->'$PRIORITY'>=$${pos}")
      params.addValue(minPriority)
    }

    val updateStatement = "UPDATE $PROCESS_CHAINS SET $STATUS=$1 " +
        "WHERE $ID = (" +
          "SELECT $ID FROM $PROCESS_CHAINS WHERE ${conditions.joinToString(" AND ")} " +
          "ORDER BY $DATA->'$PRIORITY' DESC, $SERIAL ASC LIMIT 1 " +
          "FOR UPDATE SKIP LOCKED" + // skip rows being updated concurrently
        ") RETURNING $DATA::varchar"
    val rs = client.preparedQuery(updateStatement).execute(params).await()
    return rs?.firstOrNull()?.let { JsonUtils.mapper.readValue(it.getString(0)) }
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
        startTime?.let { ISO_INSTANT.format(it) })
  }

  override suspend fun getProcessChainStartTime(processChainId: String): Instant? =
      getProcessChainColumn(processChainId, START_TIME) { it.getString(0)?.let { s ->
        Instant.from(ISO_INSTANT.parse(s)) } }

  override suspend fun setProcessChainEndTime(processChainId: String, endTime: Instant?) {
    updateColumn(PROCESS_CHAINS, processChainId, END_TIME,
        endTime?.let { ISO_INSTANT.format(it) })
  }

  override suspend fun getProcessChainEndTime(processChainId: String): Instant? =
      getProcessChainColumn(processChainId, END_TIME) { it.getString(0)?.let { s ->
        Instant.from(ISO_INSTANT.parse(s)) } }

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

  override suspend fun setProcessChainPriority(processChainId: String, priority: Int): Boolean {
    val newObj = jsonObjectOf(PRIORITY to priority)
    val updateStatement = "UPDATE $PROCESS_CHAINS SET $DATA=$DATA || $1 " +
        "WHERE $ID=$2 AND $DATA->'$PRIORITY'!=$3 AND ($STATUS=$4 OR $STATUS=$5) RETURNING 1"
    val updateParams = Tuple.of(newObj, processChainId, priority,
        ProcessChainStatus.REGISTERED.toString(), ProcessChainStatus.RUNNING.toString())
    val result = client.preparedQuery(updateStatement).execute(updateParams).await()
    return result.size() > 0
  }

  override suspend fun setAllProcessChainsPriority(submissionId: String, priority: Int) {
    val newObj = jsonObjectOf(PRIORITY to priority)
    val updateStatement = "UPDATE $PROCESS_CHAINS SET $DATA=$DATA || $1 WHERE " +
        "$SUBMISSION_ID=$2 AND ($STATUS=$3 OR $STATUS=$4)"
    val updateParams = Tuple.of(newObj, submissionId,
        ProcessChainStatus.REGISTERED.toString(), ProcessChainStatus.RUNNING.toString())
    client.preparedQuery(updateStatement).execute(updateParams).await()
  }

  override suspend fun setProcessChainResults(processChainId: String,
      results: Map<String, List<Any>>?) {
    updateColumn(PROCESS_CHAINS, processChainId, RESULTS, results?.let{ JsonUtils.toJson(it) })
  }

  override suspend fun getProcessChainResults(processChainId: String): Map<String, List<Any>>? =
      getProcessChainColumn(processChainId, "$RESULTS::varchar") { r ->
        r.getString(0)?.let { JsonUtils.mapper.readValue(it) } }

  override suspend fun getProcessChainStatusAndResultsIfFinished(processChainIds: Collection<String>):
      Map<String, Pair<ProcessChainStatus, Map<String, List<Any>>?>> {
    val statement = "SELECT $ID, $STATUS, $RESULTS::varchar FROM $PROCESS_CHAINS " +
        "WHERE $STATUS!=$1 AND $STATUS!=$2 AND $ID=ANY($3)"
    val params = Tuple.of(
        ProcessChainStatus.REGISTERED,
        ProcessChainStatus.RUNNING,
        processChainIds.toTypedArray()
    )
    val rs = client.preparedQuery(statement).execute(params).await()
    return rs.associateBy({ it.getString(0) }, { row ->
      val status = ProcessChainStatus.valueOf(row.getString(1))
      val results = row.getString(2)?.let { JsonUtils.mapper.readValue<Map<String, List<Any>>>(it) }
      status to results
    })
  }

  override suspend fun setProcessChainErrorMessage(processChainId: String,
      errorMessage: String?) {
    updateColumn(PROCESS_CHAINS, processChainId, ERROR_MESSAGE, errorMessage)
  }

  override suspend fun getProcessChainErrorMessage(processChainId: String): String? =
      getProcessChainColumn(processChainId, ERROR_MESSAGE) { it.getString(0) }

  /**
   * Escape a string so it can be used in a LIKE expression
   */
  private fun escapeLikeExpression(expr: String): String {
    return expr.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%")
  }

  /**
   * Checks if [params] contains [value], and if it does, returns its position.
   * Otherwise, creates a new position, puts it into [params] and returns it
   */
  private fun makeParam(value: Any, params: MutableMap<Any, Int>): Int =
      params.computeIfAbsent(value) { params.size + 1 }

  /**
   * Create a LIKE expression that compares [field] to a given [value]. Replaces
   * [value] with a placeholder and puts the placeholder with its position into
   * [params].
   */
  private fun makeLike(field: String, value: String, params: MutableMap<Any, Int>): String {
    val like = "%${escapeLikeExpression(value)}%"
    return "$field ILIKE $${makeParam(like, params)}"
  }

  /**
   * Create an expression that compares a timestamp stored in a [field] with
   * the given [start] time and [endExclusive] time. Puts the placeholder with
   * its position into [params].
   */
  private fun makeTimestampComparison(field: String, start: OffsetDateTime,
      endExclusive: OffsetDateTime, operator: Operator,
      params: MutableMap<Any, Int>): String {
    val f = "($field)::timestamptz"
    return when (operator) {
      Operator.LT -> "$f<$" + makeParam(start, params)
      Operator.LTE -> "$f<$" + makeParam(endExclusive, params)
      Operator.EQ -> "($f>=$${makeParam(start, params)} AND $f<$${makeParam(endExclusive, params)})"
      Operator.GTE -> "$f>=$" + makeParam(start, params)
      Operator.GT -> "$f>=$" + makeParam(endExclusive, params)
    }
  }

  /**
   * Converts a [locator] to a column name or JSONB property name
   */
  private fun locatorToField(locator: Locator, type: Type): String? = when (locator) {
    Locator.ERROR_MESSAGE -> ERROR_MESSAGE
    Locator.ID -> ID
    Locator.NAME -> when (type) {
      Type.WORKFLOW -> "$DATA->>'$NAME'"
      Type.PROCESS_CHAIN -> null
    }
    Locator.REQUIRED_CAPABILITIES -> "$DATA->'$REQUIRED_CAPABILITIES'"
    Locator.SOURCE -> when (type) {
      Type.WORKFLOW -> "$DATA->>'$SOURCE'"
      Type.PROCESS_CHAIN -> null
    }
    Locator.STATUS -> when (type) {
      Type.WORKFLOW -> "$DATA->>'$STATUS'"
      Type.PROCESS_CHAIN -> STATUS
    }
    Locator.START_TIME -> when (type) {
      Type.WORKFLOW -> "$DATA->>'$START_TIME'"
      Type.PROCESS_CHAIN -> START_TIME
    }
    Locator.END_TIME -> when (type) {
      Type.WORKFLOW -> "$DATA->>'$END_TIME'"
      Type.PROCESS_CHAIN -> END_TIME
    }
  }

  /**
   * Converts a locator to a property name in a [SearchResult] object
   */
  private fun locatorToResultName(locator: Locator) = when (locator) {
    Locator.ERROR_MESSAGE -> "errorMessage"
    Locator.ID -> "id"
    Locator.NAME -> "name"
    Locator.REQUIRED_CAPABILITIES -> "requiredCapabilities"
    Locator.SOURCE -> "source"
    Locator.STATUS -> "status"
    Locator.START_TIME -> "startTime"
    Locator.END_TIME -> "endTime"
  }

  /**
   * Creates an SQL WHERE expression from a [locator] and a [term]. Fills
   * [params] with placeholders.
   */
  private fun makeWhere(locator: Locator, term: Term, type: Type,
      params: MutableMap<Any, Int>): String? {
    return when (locator) {
      Locator.ERROR_MESSAGE, Locator.ID -> {
        when (term) {
          is StringTerm -> locatorToField(locator, type)?.let { f ->
            makeLike(f, term.value, params) }
          else -> null
        }
      }

      Locator.STATUS -> {
        when (term) {
          is StringTerm -> (when (type) {
            Type.WORKFLOW -> Submission.Status.values().find {
              it.name.contains(term.value, true) }?.name
            Type.PROCESS_CHAIN -> ProcessChainStatus.values().find {
              it.name.contains(term.value, true) }?.name
          })?.let { status -> locatorToField(locator, type)?.let { f -> "$f='$status'" } }
          else -> null
        }
      }

      // submission only!
      Locator.NAME, Locator.SOURCE -> {
        if (type == Type.WORKFLOW) {
          when (term) {
            is StringTerm -> locatorToField(locator, type)?.let { f ->
              makeLike(f, term.value, params) }
            else -> null
          }
        } else {
          null
        }
      }

      Locator.REQUIRED_CAPABILITIES -> {
        when (term) {
          is StringTerm -> makeLike("rcs_to_string(${locatorToField(locator, type)})",
              term.value, params)
          else -> null
        }
      }

      Locator.START_TIME, Locator.END_TIME -> {
        locatorToField(locator, type)?.let { f ->
          when (term) {
            is DateTerm -> makeTimestampComparison(f,
                term.value.atStartOfDay(term.timeZone).toOffsetDateTime(),
                term.value.plusDays(1).atStartOfDay(term.timeZone).toOffsetDateTime(),
                term.operator, params)

            is DateTimeTerm -> {
              if (term.withSecondPrecision) {
                makeTimestampComparison(f,
                    term.value.truncatedTo(ChronoUnit.SECONDS)
                        .atZone(term.timeZone).toOffsetDateTime(),
                    term.value.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
                        .atZone(term.timeZone).toOffsetDateTime(),
                    term.operator, params)
              } else {
                makeTimestampComparison(f,
                    term.value.truncatedTo(ChronoUnit.MINUTES)
                        .atZone(term.timeZone).toOffsetDateTime(),
                    term.value.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                        .atZone(term.timeZone).toOffsetDateTime(),
                    term.operator, params)
              }
            }

            is DateTimeRangeTerm -> {
              val start = (if (term.fromInclusiveTime != null) {
                (if (term.fromWithSecondPrecision) {
                  term.fromInclusiveDate.atTime(term.fromInclusiveTime)
                      .truncatedTo(ChronoUnit.SECONDS)
                } else {
                  term.fromInclusiveDate.atTime(term.fromInclusiveTime)
                      .truncatedTo(ChronoUnit.MINUTES)
                }).atZone(term.timeZone)
              } else {
                term.fromInclusiveDate.atStartOfDay(term.timeZone)
              }).toOffsetDateTime()

              val endExclusive = (if (term.toInclusiveTime != null) {
                (if (term.toWithSecondPrecision) {
                  term.toInclusiveDate.atTime(term.toInclusiveTime)
                      .truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
                } else {
                  term.toInclusiveDate.atTime(term.toInclusiveTime)
                      .truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                }).atZone(term.timeZone)
              } else {
                term.toInclusiveDate.plusDays(1).atStartOfDay(term.timeZone)
              }).toOffsetDateTime()

              makeTimestampComparison(f, start, endExclusive, Operator.EQ, params)
            }

            else -> null
          }
        }
      }
    }
  }

  override suspend fun search(query: Query, size: Int, offset: Int,
      order: Int): Collection<SearchResult> {
    // *************************************************************************
    //   STOP! DON'T TOUCH THIS CODE UNLESS YOU KNOW EXACTLY WHAT YOU'RE DOING
    //
    // The SQL statement generated here has been optimized for performance!
    // Days worth of effort have been put into this. Several different
    // strategies including full-text search, word similarity, various indexes
    // (GIN vs. GIST), and various approaches to the SQL statement have been
    // evaluated.
    //
    // The general idea here is to (a) reduce I/O operations during the query,
    // (b) to give the PostgreSQL query planner the possibility to make best
    // use of indexes, and (c) to reduce the number of computations and rows
    // that need to be sorted during the search.
    //
    // Approach:
    // * The statement is split into three parts. The first two parts are WITH
    //   queries (CTEs = Common Table Expressions).
    // * The first CTE fetches the IDs of the rows that match the given
    //   criteria. It does not fetch any data to reduce the number of buffers
    //   read (I/O) and to reduce the memory needed for the subsequent sort
    //   operations. All IDs are put into a union set.
    // * Note that results for each of the criteria are already limited to
    //   `maxSubMatches` here. This limits the size of the union set, which
    //   could otherwise become very large if there are many matching rows.
    //   Sorting would then take too long and too much memory would be needed.
    //   However, this might yield incorrect results (see remark below, so
    //   `maxSubMatches` is a tradeoff here.
    // * The second CTE takes the union set and counts how often each ID
    //   appears in it, i.e. how many criteria have matched. The counts are
    //   then sorted and the result is limited to the `size` argument passed
    //   to this method.
    // * The main part of the query then takes the remaining IDs and fetches
    //   the row data for them. The result needs to be sorted again because
    //   the join operation might change the order.
    //
    // *************************************************************************

    // TODO make configurable
    // The maximum number of rows to return for each term or query filter
    // (sorted by SERIAL, descending, so the newest rows will be preferred).
    // Lower values might yield incorrect results (missing combinations of
    // terms/filters), higher values might impact performance (especially
    // if a term matches a large number of rows).
    // Example for a situation that could lead to incorrect results: A query
    // contains criteria A and B. The first 1000 rows match criteria A, the
    // next 1000 rows match both criteria A and B. Normally, you would expect
    // that the rows matching both criteria should be picked first, but in this
    // case, this doesn't work. The result set for A is limited to 1000 and
    // A will not be counted for the next 1000 rows. So, all rows get a rank
    // of 1 matching criterion. Sorted by rank and serial, the first 1000 rows
    // (matching only A) will stay at the top.
    // Solution for this: Either increase `maxSubMatches` or include a filter
    // in the query that matches B. Filters are like AND-expressions, so with
    // a filter, the first 1000 rows will actually not be included in the
    // result at all.
    val maxSubMatches = 1000

    if (query == Query()) {
      return emptyList()
    }

    val asc = if (order >= 0) "ASC" else "DESC"
    val desc = if (order >= 0) "DESC" else "ASC"
    val limit = if (size < 0) "ALL" else size.toString()

    // search in all places by default
    val types = query.types.ifEmpty { Type.values().toSet() }
    val locators = if (query.terms.isNotEmpty()) {
      query.locators.ifEmpty { Locator.values().toSet() }
    } else {
      emptyList()
    }

    // create SELECT statements for all types and all conditions
    val substatements = mutableSetOf<String>()
    val params = mutableMapOf<Any, Int>()
    for (type in types) {
      val table = when (type) {
        Type.PROCESS_CHAIN -> PROCESS_CHAINS
        Type.WORKFLOW -> SUBMISSIONS
      }

      // make a WHERE expression for each filter
      val whereFilters = mutableSetOf<String>()
      for (f in query.filters) {
        makeWhere(f.first, f.second, type, params)?.let { whereFilters.add(it) }
      }

      // make a SELECT statement for each term
      var substatementsAdded = false
      for (term in query.terms) {
        val whereTerms = mutableSetOf<String>()
        for (locator in locators) {
          makeWhere(locator, term, type, params)?.let { whereTerms.add(it) }
        }
        if (whereTerms.isNotEmpty()) {
          val joinedWhereTerms = "(${whereTerms.joinToString(" OR ")})"
          val joinedWhereFilters = "(${whereFilters.joinToString(" AND ")})"
          val where = if (whereFilters.isNotEmpty()) {
            "$joinedWhereTerms AND $joinedWhereFilters"
          } else {
            joinedWhereTerms
          }
          substatements.add("SELECT $ID,$SERIAL,${type.priority} AS type FROM $table " +
              "WHERE $where ORDER BY $SERIAL $desc LIMIT $maxSubMatches")
          substatementsAdded = true
        }
      }

      // make a SELECT statement for each filter (but only if there aren't any
      // substatements for this type yet - i.e. if there are no terms or if the
      // term/locator combinations did not lead to any substatement)
      if (!substatementsAdded) {
        for (where in whereFilters) {
          substatements.add("SELECT $ID,$SERIAL,${type.priority} AS type FROM $table " +
              "WHERE $where " +
              "ORDER BY $SERIAL $desc LIMIT $maxSubMatches")
        }
      }
    }

    if (substatements.isEmpty()) {
      // nothing to do
      return emptyList()
    }

    // determine which columns we need to return (some fields are mandatory
    // in SearchResults)
    val columns = mutableSetOf(Locator.ID, Locator.NAME, Locator.STATUS,
        Locator.REQUIRED_CAPABILITIES, Locator.START_TIME, Locator.END_TIME)
    for (l in locators) {
      columns.add(l)
    }
    for ((l, _) in query.filters) {
      columns.add(l)
    }

    // union all sub-statements, group the results by ID, count the number of
    // matches per row, and sort by this count
    val withStatement =
        "WITH results AS ((${substatements.joinToString(") UNION ALL (")}))," +
        "ranking AS (SELECT $ID,$SERIAL,type,COUNT(*) AS rank FROM results " +
        "GROUP BY ($ID,$SERIAL,type) ORDER BY COUNT(*) $desc, type $asc,$SERIAL $desc " +
        "LIMIT $limit OFFSET $offset)"

    // now that we have the IDs of the rows to return, fetch the actual data
    val statements = mutableSetOf<String>()
    for (type in types) {
      val table = when (type) {
        Type.PROCESS_CHAIN -> PROCESS_CHAINS
        Type.WORKFLOW -> SUBMISSIONS
      }
      val tcs = columns.map { l ->
        val f = locatorToField(l, type)
        val c = if (f == null) "NULL" else "$table.$f"
        "$c AS \"${locatorToResultName(l)}\""
      }
      statements.add("SELECT ${tcs.joinToString(",")},ranking.rank,ranking.serial,ranking.type " +
          "FROM $table JOIN ranking ON $table.$ID = ranking.$ID")
    }

    val statement = "$withStatement ${statements.joinToString(" UNION ALL ")} " +
        "ORDER BY rank $desc,type $asc,$SERIAL $desc"

    val rs = client.preparedQuery(statement).execute(Tuple.from(params.keys.toList())).await()
    return rs.map { row ->
      val obj = row.toJson()
      // remove auxiliary columns
      obj.remove("rank")
      obj.remove(SERIAL)
      JsonUtils.fromJson(obj)
    }
  }

  override suspend fun searchCount(query: Query, type: Type, estimate: Boolean): Long {
    // search in all places by default
    val locators = if (query.terms.isNotEmpty()) {
      query.locators.ifEmpty { Locator.values().toSet() }
    } else {
      emptyList()
    }

    val table = when (type) {
      Type.PROCESS_CHAIN -> PROCESS_CHAINS
      Type.WORKFLOW -> SUBMISSIONS
    }

    val params = mutableMapOf<Any, Int>()

    // make a WHERE expression for each filter
    val whereFilters = mutableSetOf<String>()
    for (f in query.filters) {
      makeWhere(f.first, f.second, type, params)?.let { whereFilters.add(it) }
    }

    // make a WHERE expression for each term/location combination
    val whereTerms = mutableSetOf<String>()
    for (term in query.terms) {
      for (locator in locators) {
        makeWhere(locator, term, type, params)?.let { whereTerms.add(it) }
      }
    }

    if (whereTerms.isEmpty() && whereFilters.isEmpty()) {
      // nothing to do
      return 0L
    }

    val joinedWhereTerms = "(${whereTerms.joinToString(" OR ")})"
    val joinedWhereFiltersAnd = "(${whereFilters.joinToString(" AND ")})"
    val joinedWhereFiltersOr = "(${whereFilters.joinToString(" OR ")})"
    val where = if (whereTerms.isNotEmpty() && whereFilters.isNotEmpty()) {
      "$joinedWhereTerms AND $joinedWhereFiltersAnd"
    } else if (whereTerms.isNotEmpty()) {
      joinedWhereTerms
    } else {
      joinedWhereFiltersOr
    }

    return if (estimate) {
      val statement = "EXPLAIN (FORMAT JSON, TIMING FALSE) SELECT 1 from $table WHERE $where"
      val rs = client.preparedQuery(statement).execute(Tuple.from(params.keys.toList())).await()
      rs?.firstOrNull()?.getJsonArray(0)?.getJsonObject(0)?.getJsonObject("Plan")
          ?.getLong("Plan Rows") ?: 0L
    } else {
      val statement = "SELECT COUNT(*) FROM $table WHERE $where"
      val rs = client.preparedQuery(statement).execute(Tuple.from(params.keys.toList())).await()
      rs?.firstOrNull()?.getLong(0) ?: 0L
    }
  }
}
