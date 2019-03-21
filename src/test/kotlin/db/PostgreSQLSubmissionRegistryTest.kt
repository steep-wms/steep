package db

import io.vertx.core.Vertx
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach

/**
 * Tests for the [PostgreSQLSubmissionRegistry]
 * @author Michel Kraemer
 */
class PostgreSQLSubmissionRegistryTest : SubmissionRegistryTest() {
  companion object {
    @AfterAll
    @JvmStatic
    fun shutdown() {
      PostgreSQLTestUtils.shutdown()
    }
  }

  override fun createRegistry(vertx: Vertx): SubmissionRegistry {
    return PostgreSQLSubmissionRegistry(vertx, PostgreSQLTestUtils.URL,
        "user", "password")
  }

  /**
   * Clear database after each test
   */
  @AfterEach
  fun clearDatabase(vertx: Vertx, ctx: VertxTestContext) {
    PostgreSQLTestUtils.clearDatabase(vertx, ctx)
  }
}
