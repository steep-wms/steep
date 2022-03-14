package db

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.await
import io.vertx.pgclient.PgPool
import org.testcontainers.containers.PostgreSQLContainerProvider
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Tests for the [PostgreSQLSubmissionRegistry]
 * @author Michel Kraemer
 */
@Testcontainers
class PostgreSQLSubmissionRegistryTest : PostgreSQLTest, SubmissionRegistryTest() {
  companion object {
    @Container
    val _postgresql = PostgreSQLContainerProvider().newInstance(PostgreSQLTest.TAG)!!
  }

  override val postgresql = _postgresql

  override fun createRegistry(vertx: Vertx): SubmissionRegistry {
    return PostgreSQLSubmissionRegistry(vertx, postgresql.jdbcUrl,
        postgresql.username, postgresql.password)
  }

  override suspend fun deleteFromTables(client: PgPool) {
    client.query("DELETE FROM submissions").execute().await()
    client.query("DELETE FROM processchains").execute().await()
  }
}
