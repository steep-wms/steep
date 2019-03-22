package db

import io.vertx.core.Vertx
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.kotlin.ext.sql.updateAwait
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.testcontainers.jdbc.ContainerDatabaseDriver

/**
 * Tests for the [PostgreSQLSubmissionRegistry]
 * @author Michel Kraemer
 */
class PostgreSQLSubmissionRegistryTest : SubmissionRegistryTest() {
  companion object {
    /**
     * Use Testcontainers' feature to automatically create a container for a
     * JDBC database if you add the tc: prefix to the URL. Use TC_DAEMON=true
     * to keep the container running until all tests have finished
     */
    const val URL = "jdbc:tc:postgresql:10.5://hostname/databasename?TC_DAEMON=true"

    /**
     * Kill the test container automatically created by Testcontainers as soon
     * as all tests have finished
     */
    @AfterAll
    @JvmStatic
    fun shutdown() {
      ContainerDatabaseDriver.killContainer(URL)
    }
  }

  override fun createRegistry(vertx: Vertx): SubmissionRegistry {
    return PostgreSQLSubmissionRegistry(vertx, URL, "user", "password")
  }

  /**
   * Clear database after each test
   */
  @AfterEach
  fun clearDatabase(vertx: Vertx, ctx: VertxTestContext) {
    val jdbcConfig = json {
      obj(
          "url" to URL,
          "user" to "user",
          "password" to "password"
      )
    }
    val client = JDBCClient.createShared(vertx, jdbcConfig)

    GlobalScope.launch(vertx.dispatcher()) {
      client.updateAwait("DELETE FROM submissions")
      client.updateAwait("DELETE FROM processchains")
      ctx.completeNow()
    }
  }
}
