package db

import io.vertx.core.Vertx
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.kotlin.ext.sql.updateAwait

/**
 * Tests for the [PostgreSQLVMRegistry]
 * @author Michel Kraemer
 */
class PostgreSQLVMRegistryTest : PostgreSQLTest, VMRegistryTest() {
  override fun createRegistry(vertx: Vertx): VMRegistry {
    return PostgreSQLVMRegistry(vertx, PostgreSQLTest.URL, "user", "password")
  }

  override suspend fun deleteFromTables(client: JDBCClient) {
    client.updateAwait("DELETE FROM vms")
  }
}
