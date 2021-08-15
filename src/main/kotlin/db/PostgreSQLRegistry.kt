package db

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.sqlclient.executeAwait
import io.vertx.kotlin.sqlclient.getConnectionAwait
import io.vertx.pgclient.PgConnectOptions
import io.vertx.pgclient.PgPool
import io.vertx.pgclient.impl.PgConnectionUriParser
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Tuple
import org.flywaydb.core.Flyway

fun Row.getJsonObject(column: Int): JsonObject = get(JsonObject::class.java, column)
fun Row.getJsonObjectOrNull(column: Int): JsonObject? = getValue(0) as JsonObject?
fun Row.getJsonArray(column: Int): JsonArray = get(JsonArray::class.java, column)

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
    internal data class MigratedDatabase(val url: String, val user: String, val password: String)

    /**
     * Keeps all databases that have already been migrated
     */
    internal val migratedDatabases = mutableSetOf<MigratedDatabase>()

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

  protected val client: PgPool

  init {
    migrate(url, username, password)

    val uri = if (url.startsWith("jdbc:")) url.substring(5) else url
    val parsedConfiguration = PgConnectionUriParser.parse(uri)
    val connectOptions = PgConnectOptions(parsedConfiguration)
        .setUser(username)
        .setPassword(password)
    if (!parsedConfiguration.containsKey("search_path") &&
        parsedConfiguration.containsKey("currentschema")) {
      connectOptions.addProperty("search_path",
          parsedConfiguration.getString("currentschema"))
    }

    val poolOptions = PoolOptions()
        .setMaxSize(5)

    client = PgPool.pool(vertx, connectOptions, poolOptions)
  }

  override suspend fun close() {
    client.close()
  }

  protected suspend fun <T> withConnection(block: suspend (SqlConnection) -> T): T {
    val connection = client.getConnectionAwait()
    try {
      return block(connection)
    } finally {
      connection.close()
    }
  }

  protected suspend fun updateProperties(table: String, id: String, newObj: JsonObject,
      connection: SqlConnection) {
    val updateStatement = "UPDATE $table SET $DATA=$DATA || $1 WHERE $ID=$2"
    val updateParams = Tuple.of(newObj, id)
    connection.preparedQuery(updateStatement).executeAwait(updateParams)
  }

  protected suspend fun updateProperties(table: String, id: String, newObj: JsonObject) {
    withConnection { connection ->
      updateProperties(table, id, newObj, connection)
    }
  }

  protected suspend fun updateColumn(table: String, id: String, column: String,
      newValue: Any?) {
    val updateStatement = "UPDATE $table SET $column=$1 WHERE $ID=$2"
    val updateParams = Tuple.of(newValue, id)
    client.preparedQuery(updateStatement).executeAwait(updateParams)
  }

  protected suspend fun updateColumn(table: String, id: String, column: String,
      currentValue: Any?, newValue: Any?) {
    val updateStatement = "UPDATE $table SET $column=$1 WHERE $ID=$2 " +
        "AND $column=$3"
    val updateParams = Tuple.of(newValue, id, currentValue)
    client.preparedQuery(updateStatement).executeAwait(updateParams)
  }
}
