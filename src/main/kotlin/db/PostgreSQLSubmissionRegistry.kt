package db

import com.fasterxml.jackson.module.kotlin.readValue
import db.SubmissionRegistry.ProcessChainStatus
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.sql.SQLConnection
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.sql.batchWithParamsAwait
import io.vertx.kotlin.ext.sql.closeAwait
import io.vertx.kotlin.ext.sql.commitAwait
import io.vertx.kotlin.ext.sql.getConnectionAwait
import io.vertx.kotlin.ext.sql.queryAwait
import io.vertx.kotlin.ext.sql.querySingleWithParamsAwait
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import io.vertx.kotlin.ext.sql.rollbackAwait
import io.vertx.kotlin.ext.sql.setAutoCommitAwait
import io.vertx.kotlin.ext.sql.updateWithParamsAwait
import model.Submission
import model.processchain.ProcessChain
import org.flywaydb.core.Flyway

/**
 * A submission registry that keeps objects in a PostgreSQL database
 * @param vertx the current Vert.x instance
 * @param url the JDBC url to the database
 * @param user the username
 * @param password the password
 * @author Michel Kraemer
 */
class PostgreSQLSubmissionRegistry(vertx: Vertx, url: String,
    user: String, password: String) : SubmissionRegistry {
  companion object {
    /**
     * Table and column names
     */
    private const val SUBMISSIONS = "submissions"
    private const val PROCESS_CHAINS = "processchains"
    private const val ID = "id"
    private const val SUBMISSION_ID = "submissionId"
    private const val DATA = "data"
    private const val STATUS = "status"
    private const val OUTPUT = "output"
  }

  private val client: JDBCClient

  init {
    migrate(url, user, password)

    val jdbcConfig = json {
      obj(
          "url" to url,
          "user" to user,
          "password" to password
      )
    }
    client = JDBCClient.createShared(vertx, jdbcConfig)
  }

  /**
   * Perform database migrations
   * @param url the JDBC url to the database
   * @param user the username
   * @param password the password
   */
  private fun migrate(url: String, user: String, password: String) {
    val flyway = Flyway.configure()
        .dataSource(url, user, password)
        .load()
    flyway.migrate()
  }

  private suspend fun <T> withConnection(block: suspend (SQLConnection) -> T): T {
    val connection = client.getConnectionAwait()
    try {
      return block(connection)
    } finally {
      connection.closeAwait()
    }
  }

  private suspend fun <T> withTransaction(block: suspend (SQLConnection) -> T): T {
    return withConnection { connection ->
      connection.setAutoCommitAwait(false)
      try {
        val r = block(connection)
        connection.commitAwait()
        r
      } catch (e: Exception) {
        try {
          connection.rollbackAwait()
        } catch (e1: Exception) {
          // ignore
        }
        throw e
      }
    }
  }

  override suspend fun addSubmission(submission: Submission) {
    withConnection { connection ->
      val statement = "INSERT INTO $SUBMISSIONS ($ID, $DATA) VALUES (?, ?::jsonb)"
      val params = json {
        array(
            submission.id,
            JsonUtils.mapper.writeValueAsString(submission)
        )
      }
      connection.updateWithParamsAwait(statement, params)
    }
  }

  override suspend fun findSubmissions(): Collection<Submission> {
    return withConnection { connection ->
      val rs = connection.queryAwait("SELECT $DATA FROM $SUBMISSIONS")
      rs.results.map { JsonUtils.mapper.readValue<Submission>(it.getString(0)) }
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
      rs?.let { JsonUtils.mapper.readValue<Submission>(it.getString(0)) }
    }
  }

  private suspend fun updateProperties(table: String, id: String, newObj: JsonObject,
      connection: SQLConnection) {
    val updateStatement = "UPDATE $table SET $DATA=$DATA || ?::jsonb WHERE $ID=?"
    val updateParams = json {
      array(
          newObj.encode(),
          id
      )
    }
    connection.updateWithParamsAwait(updateStatement, updateParams)
  }

  private suspend fun updateProperties(table: String, id: String, newObj: JsonObject) {
    withConnection { connection ->
      updateProperties(table, id, newObj, connection)
    }
  }

  private suspend fun updateColumn(table: String, id: String, column: String,
      newValue: Any, jsonb: Boolean, connection: SQLConnection) {
    val jsonbStr = if (jsonb) "::jsonb" else ""
    val updateStatement = "UPDATE $table SET $column=?$jsonbStr WHERE $ID=?"
    val updateParams = json {
      array(
          newValue,
          id
      )
    }
    connection.updateWithParamsAwait(updateStatement, updateParams)
  }

  private suspend fun updateColumn(table: String, id: String, column: String,
      newValue: Any, jsonb: Boolean) {
    withConnection { connection ->
      updateColumn(table, id, column, newValue, jsonb, connection)
    }
  }

  override suspend fun fetchNextSubmission(currentStatus: Submission.Status,
      newStatus: Submission.Status): Submission? {
    return withTransaction { connection ->
      val statement = "SELECT $DATA FROM $SUBMISSIONS WHERE $DATA->'$STATUS'=?::jsonb LIMIT 1"
      val params = json {
        array(
            "\"$currentStatus\""
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { arr ->
        JsonUtils.mapper.readValue<Submission>(arr.getString(0)).also {
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

  override suspend fun setSubmissionStatus(submissionId: String, status: Submission.Status) {
    val newObj = json {
      obj(
          STATUS to status.toString()
      )
    }
    updateProperties(SUBMISSIONS, submissionId, newObj)
  }

  override suspend fun addProcessChains(processChains: Collection<ProcessChain>,
      submissionId: String, status: ProcessChainStatus) {
    withTransaction { connection ->
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
              JsonUtils.mapper.writeValueAsString(it)
          )
        }
      }
      connection.batchWithParamsAwait(insertStatement, insertParams)
    }
  }

  override suspend fun findProcessChainsBySubmissionId(submissionId: String): Collection<ProcessChain> {
    return withConnection { connection ->
      val statement = "SELECT $DATA FROM $PROCESS_CHAINS WHERE $SUBMISSION_ID=?"
      val params = json {
        array(
            submissionId
        )
      }
      val rs = connection.queryWithParamsAwait(statement, params)
      rs.results.map { JsonUtils.mapper.readValue<ProcessChain>(it.getString(0)) }
    }
  }

  override suspend fun fetchNextProcessChain(currentStatus: ProcessChainStatus,
      newStatus: ProcessChainStatus): ProcessChain? {
    return withTransaction { connection ->
      val statement = "SELECT $DATA FROM $PROCESS_CHAINS WHERE $STATUS=? LIMIT 1"
      val params = json {
        array(
            currentStatus.toString()
        )
      }
      val rs = connection.querySingleWithParamsAwait(statement, params)
      rs?.let { arr ->
        JsonUtils.mapper.readValue<ProcessChain>(arr.getString(0)).also {
          updateColumn(PROCESS_CHAINS, it.id, STATUS, newStatus.toString(),
              false, connection)
        }
      }
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

  override suspend fun setProcessChainStatus(processChainId: String,
      status: ProcessChainStatus) {
    updateColumn(PROCESS_CHAINS, processChainId, STATUS, status.toString(), false)
  }

  override suspend fun getProcessChainStatus(processChainId: String): ProcessChainStatus {
    return getProcessChainColumn(processChainId, STATUS) { r ->
      r.getString(0).let { ProcessChainStatus.valueOf(it) }
    }
  }

  override suspend fun setProcessChainOutput(processChainId: String,
      output: Map<String, List<String>>?) {
    updateColumn(PROCESS_CHAINS, processChainId, OUTPUT,
        JsonUtils.mapper.writeValueAsString(output), true)
  }

  override suspend fun getProcessChainOutput(processChainId: String): Map<String, List<String>>? {
    return getProcessChainColumn(processChainId, OUTPUT) { r ->
      r.getString(0)?.let { JsonUtils.mapper.readValue<Map<String, List<String>>>(it) }
    }
  }
}
