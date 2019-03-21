package db

import io.vertx.core.Vertx
import io.vertx.ext.sql.SQLClient
import io.vertx.ext.sql.SQLConnection
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.ext.jdbc.querySingleWithParamsAwait
import io.vertx.kotlin.ext.sql.batchWithParamsAwait
import io.vertx.kotlin.ext.sql.closeAwait
import io.vertx.kotlin.ext.sql.getConnectionAwait
import io.vertx.kotlin.ext.sql.queryWithParamsAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.workflow.Variable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Tests [PostgreSQLPersistentMap]
 * @author Michel Kraemer
 */
class PostgreSQLPersistentMapTest : PersistentMapTest() {
  companion object {
    @AfterAll
    @JvmStatic
    fun shutdown() {
      PostgreSQLTestUtils.shutdown()
    }
  }

  private lateinit var client: SQLClient
  private var connection: SQLConnection? = null

  /**
   * Prepare the PostgreSQL database
   */
  @BeforeEach
  fun setUp(vertx: Vertx) {
    // perform migrations
    val flyway = Flyway.configure()
        .dataSource(PostgreSQLTestUtils.URL, "user", "password")
        .load()
    flyway.migrate()

    // create SQL client
    client = PostgreSQLTestUtils.createJDBCClient(vertx)
  }

  /**
   * Clear the database after each test
   */
  @AfterEach
  fun tearDown(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch {
      client.closeAwait()
      PostgreSQLTestUtils.clearDatabase(vertx, ctx)
    }
  }

  /**
   * Get or create an SQL connection
   */
  private suspend fun getConnection(): SQLConnection {
    if (connection == null) {
      connection = client.getConnectionAwait()
    }
    return connection!!
  }

  override suspend fun <K, V> createMap(name: String, keySerialize: (K) -> String,
      keyDeserialize: (String) -> K, valueSerialize: (V) -> String,
      valueDeserialize: (String) -> V): PersistentMap<K, V> {
    val connection = getConnection()
    return PostgreSQLPersistentMap<K, V>(name, connection,
        keySerialize, keyDeserialize, valueSerialize, valueDeserialize).load()
  }

  override suspend fun prepareLoadString(vertx: Vertx): Map<String, String> {
    val client = PostgreSQLTestUtils.createJDBCClient(vertx)
    val statement = "INSERT INTO persistentmap (name, k, v) VALUES (?, ?, ?)"
    val args = listOf(
        json {
          array(PERSISTENT_MAP_NAME, "0", "B")
        },
        json {
          array(PERSISTENT_MAP_NAME, "1", "C")
        }
    )
    val connection = client.getConnectionAwait()
    try {
      connection.batchWithParamsAwait(statement, args)
      return mapOf("0" to "B", "1" to "C")
    } finally {
      connection.closeAwait()
    }
  }

  override suspend fun prepareLoadVariable(vertx: Vertx,
      valueSerialize: (Variable) -> String): Map<String, Variable> {
    val v1 = Variable(value = "A")
    val v2 = Variable(value = "B")
    val client = PostgreSQLTestUtils.createJDBCClient(vertx)
    val statement = "INSERT INTO persistentmap (name, k, v) VALUES (?, ?, ?)"
    val args = listOf(
        json {
          array(PERSISTENT_MAP_NAME, "0", valueSerialize(v1))
        },
        json {
          array(PERSISTENT_MAP_NAME, "1", valueSerialize(v2))
        }
    )
    val connection = client.getConnectionAwait()
    try {
      connection.batchWithParamsAwait(statement, args)
      return mapOf("0" to v1, "1" to v2)
    } finally {
      connection.closeAwait()
    }
  }

  override suspend fun verifySize(vertx: Vertx, expectedSize: Int) {
    val client = PostgreSQLTestUtils.createJDBCClient(vertx)
    val params = json {
      array(PERSISTENT_MAP_NAME)
    }
    val statement = "SELECT COUNT(*) FROM persistentmap WHERE name=?"
    val rs = client.querySingleWithParamsAwait(statement, params)!!
    assertThat(rs.getInteger(0)).isEqualTo(expectedSize)
  }

  override suspend fun <V> verifyPersist(vertx: Vertx, expectedMap: Map<String, V>,
      expectedSize: Int, valueSerialize: (V) -> String) {
    val client = PostgreSQLTestUtils.createJDBCClient(vertx)
    val params = json {
      array(PERSISTENT_MAP_NAME)
    }
    val statement = "SELECT k,v FROM persistentmap WHERE name=?"
    val rs = client.queryWithParamsAwait(statement, params)
    val lm = mutableMapOf<String, String>()
    for (r in rs.results) {
      lm[r.getString(0)] = r.getString(1)
    }
    assertThat(lm).hasSize(expectedSize)
    for ((k, v) in expectedMap) {
      assertThat(lm).contains(entry(k, valueSerialize(v)))
    }
  }
}
