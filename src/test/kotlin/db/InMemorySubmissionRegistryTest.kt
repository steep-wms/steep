package db

import io.vertx.core.Vertx

/**
 * Tests for [InMemorySubmissionRegistry]
 * @author Michel Kraemer
 */
class InMemorySubmissionRegistryTest : SubmissionRegistryTest() {
  override fun createRegistry(vertx: Vertx) = InMemorySubmissionRegistry(vertx)
}
