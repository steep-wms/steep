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
import org.testcontainers.jdbc.ContainerDatabaseDriver

/**
 * Helpers for tests that use PostgreSQL
 * @author Michel Kraemer
 */
object PostgreSQLTestUtils {
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
  fun shutdown() {
    ContainerDatabaseDriver.killContainer(URL)
  }

  /**
   * Create a JDBC client
   * @param vertx the current Vert.x instance
   */
  fun createJDBCClient(vertx: Vertx): JDBCClient {
    val jdbcConfig = json {
      obj(
          "url" to URL,
          "user" to "user",
          "password" to "password"
      )
    }
    return JDBCClient.createShared(vertx, jdbcConfig)
  }

  /**
   * Clear database after each test
   * @param vertx the Vert.x instance
   * @param ctx the current test context
   */
  fun clearDatabase(vertx: Vertx, ctx: VertxTestContext) {
    val client = createJDBCClient(vertx)
    GlobalScope.launch(vertx.dispatcher()) {
      client.updateAwait("DELETE FROM submissions")
      client.updateAwait("DELETE FROM processchains")
      client.updateAwait("DELETE FROM persistentmap")
      ctx.completeNow()
    }
  }
}
