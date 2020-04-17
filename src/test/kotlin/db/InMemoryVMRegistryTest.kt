package db

import io.vertx.core.Vertx

/**
 * Tests for [InMemoryVMRegistry]
 * @author Michel Kraemer
 */
class InMemoryVMRegistryTest : VMRegistryTest() {
  override fun createRegistry(vertx: Vertx) = InMemoryVMRegistry(vertx)
}
