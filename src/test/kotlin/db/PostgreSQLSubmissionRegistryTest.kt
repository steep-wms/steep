package db

import io.vertx.core.Vertx
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.kotlin.ext.sql.updateAwait

/**
 * Tests for the [PostgreSQLSubmissionRegistry]
 * @author Michel Kraemer
 */
class PostgreSQLSubmissionRegistryTest : PostgreSQLTest, SubmissionRegistryTest() {
  override fun createRegistry(vertx: Vertx): SubmissionRegistry {
    return PostgreSQLSubmissionRegistry(vertx, PostgreSQLTest.URL, "user", "password")
  }

  override suspend fun deleteFromTables(client: JDBCClient) {
    client.updateAwait("DELETE FROM submissions")
    client.updateAwait("DELETE FROM processchains")
  }
}
