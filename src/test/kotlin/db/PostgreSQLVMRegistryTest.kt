package db

import io.vertx.core.Vertx
import io.vertx.kotlin.sqlclient.executeAwait
import io.vertx.pgclient.PgPool
import org.testcontainers.containers.PostgreSQLContainerProvider
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Tests for the [PostgreSQLVMRegistry]
 * @author Michel Kraemer
 */
@Testcontainers
class PostgreSQLVMRegistryTest : PostgreSQLTest, VMRegistryTest() {
  companion object {
    @Container
    val _postgresql = PostgreSQLContainerProvider().newInstance(PostgreSQLTest.TAG)
  }

  override val postgresql = _postgresql

  override fun createRegistry(vertx: Vertx): VMRegistry {
    return PostgreSQLVMRegistry(vertx, postgresql.jdbcUrl, postgresql.username,
        postgresql.password)
  }

  override suspend fun deleteFromTables(client: PgPool) {
    client.query("DELETE FROM vms").executeAwait()
  }
}
