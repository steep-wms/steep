package db

import io.vertx.core.Vertx

/**
 * Tests for [MongoDBVMRegistry]
 * @author Michel Kraemer
 */
class MongoDBVMRegistryTest : MongoDBTest, VMRegistryTest() {
  override fun createRegistry(vertx: Vertx) = MongoDBVMRegistry(vertx,
      MongoDBTest.CONNECTION_STRING, false)
}
