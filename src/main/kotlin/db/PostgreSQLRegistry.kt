package db

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.jdbc.spi.impl.HikariCPDataSourceProvider
import io.vertx.ext.sql.SQLConnection
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.ext.sql.closeAwait
import io.vertx.kotlin.ext.sql.getConnectionAwait
import io.vertx.kotlin.ext.sql.updateWithParamsAwait
import org.flywaydb.core.Flyway

/**
 * Base class for registries that access a PostgreSQL database
 * @param url the JDBC url to the database
 * @param username the username
 * @param password the password
 * @author Michel Kraemer
 */
open class PostgreSQLRegistry(vertx: Vertx, url: String, username: String,
    password: String) : Registry {
  companion object {
    /**
     * Table and column names
     */
    @JvmStatic protected val ID = "id"
    @JvmStatic protected val DATA = "data"

    /**
     * Holds information about a database that has already been migrated
     */
    private data class MigratedDatabase(val url: String, val user: String, val password: String)

    /**
     * Keeps all databases that have already been migrated
     */
    private val migratedDatabases = mutableSetOf<MigratedDatabase>()

    /**
     * Perform database migrations
     * @param url the JDBC url to the database
     * @param user the username
     * @param password the password
     */
    @Synchronized
    private fun migrate(url: String, user: String, password: String) {
      val md = MigratedDatabase(url, user, password)
      if (!migratedDatabases.contains(md)) {
        val flyway = Flyway.configure()
            .dataSource(url, user, password)
            .load()
        flyway.migrate()
        migratedDatabases.add(md)
      }
    }
  }

  private val client: JDBCClient

  init {
    migrate(url, username, password)

    val jdbcConfig = json {
      obj(
          "provider_class" to HikariCPDataSourceProvider::class.java.name,
          "jdbcUrl" to url,
          "username" to username,
          "password" to password,
          "minimumIdle" to 0,
          "maximumPoolSize" to 5
      )
    }
    client = JDBCClient.createShared(vertx, jdbcConfig)
  }

  override suspend fun close() {
    client.closeAwait()
  }

  protected suspend fun <T> withConnection(block: suspend (SQLConnection) -> T): T {
    val connection = client.getConnectionAwait()
    try {
      return block(connection)
    } finally {
      connection.closeAwait()
    }
  }

  protected suspend fun updateProperties(table: String, id: String, newObj: JsonObject,
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

  protected suspend fun updateProperties(table: String, id: String, newObj: JsonObject) {
    withConnection { connection ->
      updateProperties(table, id, newObj, connection)
    }
  }

  protected suspend fun updateColumn(table: String, id: String, column: String,
      newValue: Any?, jsonb: Boolean, connection: SQLConnection) {
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

  protected suspend fun updateColumn(table: String, id: String, column: String,
      newValue: Any?, jsonb: Boolean) {
    withConnection { connection ->
      updateColumn(table, id, column, newValue, jsonb, connection)
    }
  }

  protected suspend fun updateColumn(table: String, id: String, column: String,
      currentValue: Any?, newValue: Any?, jsonb: Boolean, connection: SQLConnection) {
    val jsonbStr = if (jsonb) "::jsonb" else ""
    val updateStatement = "UPDATE $table SET $column=?$jsonbStr WHERE $ID=? " +
        "AND $column=?$jsonbStr"
    val updateParams = json {
      array(
          newValue,
          id,
          currentValue
      )
    }
    connection.updateWithParamsAwait(updateStatement, updateParams)
  }

  protected suspend fun updateColumn(table: String, id: String, column: String,
      currentValue: Any?, newValue: Any?, jsonb: Boolean) {
    withConnection { connection ->
      updateColumn(table, id, column, currentValue, newValue, jsonb, connection)
    }
  }
}
