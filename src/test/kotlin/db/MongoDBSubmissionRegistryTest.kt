package db

import io.vertx.core.Vertx

/**
 * Tests for [MongoDBSubmissionRegistry]
 * @author Michel Kraemer
 */
class MongoDBSubmissionRegistryTest : MongoDBTest, SubmissionRegistryTest() {
  override fun createRegistry(vertx: Vertx) = MongoDBSubmissionRegistry(
      vertx, MongoDBTest.CONNECTION_STRING, false)
}
