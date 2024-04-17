package db

import io.vertx.core.Vertx
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import io.vertx.pgclient.PgBuilder
import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.testcontainers.containers.JdbcDatabaseContainer

/**
 * Common code for all tests that need a PostgreSQL database
 * @author MicheL Kraemer
 */
interface PostgreSQLTest {
  companion object {
    const val TAG = "10.5"
  }

  val postgresql: JdbcDatabaseContainer<*>

  /**
   * Clear database after each test
   */
  @AfterEach
  fun tearDownDatabase(ctx: VertxTestContext) {
    val url = postgresql.jdbcUrl.let { if (it.startsWith("jdbc:")) it.substring(5) else it }
    val connectOptions = PgConnectOptions.fromUri(url)
        .setUser(postgresql.username)
        .setPassword(postgresql.password)

    // we need a new Vert.x instance here, because the injected one might
    // already be closed if the test has failed
    val isolatedVertx = Vertx.vertx()

    val poolOptions = PoolOptions()
        .setMaxSize(5)
    val client = PgBuilder.pool()
        .with(poolOptions)
        .connectingTo(connectOptions)
        .using(isolatedVertx)
        .build()

    CoroutineScope(isolatedVertx.dispatcher()).launch {
      deleteFromTables(client)
      client.close()
      isolatedVertx.close()
      ctx.completeNow()
    }

    // make sure migrations will run for the next unit test
    PostgreSQLRegistry.migratedDatabases.clear()
  }

  /**
   * Will be called after each test to clean up all tables
   */
  suspend fun deleteFromTables(client: Pool)
}
