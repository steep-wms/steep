package db

import io.vertx.core.Vertx
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Tests for [MongoDBSubmissionRegistry]
 * @author Michel Kraemer
 */
class MongoDBSubmissionRegistryTest : MongoDBTest, SubmissionRegistryTest() {
  override fun createRegistry(vertx: Vertx) = MongoDBSubmissionRegistry(
      vertx, MongoDBTest.CONNECTION_STRING, false)

  // TODO remove overrides once search has been implemented for MongoDB
  // TODO remove `open` keyword from override methods in superclass
  @Test @Disabled
  override fun searchCount(vertx: Vertx, ctx: VertxTestContext) {}
}
