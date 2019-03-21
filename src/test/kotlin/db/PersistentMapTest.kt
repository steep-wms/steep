package db

import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.workflow.Variable
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Base class for unit tests that test [PersistentMap]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
abstract class PersistentMapTest {
  companion object {
    const val PERSISTENT_MAP_NAME = "PersistentMap"
  }

  /**
   * Create a persistent map with a given [name]
   */
  private suspend inline fun <reified V> createMap(name: String) = createMap(name, V::class.java)

  /**
   * Create a persistent map with a given [name] and value type [cls]
   */
  protected abstract suspend fun <V> createMap(name: String, cls: Class<V>): PersistentMap<V>

  /**
   * Put some [String]s into the persistent map that can then be loaded by
   * another instance. Return these entries.
   */
  protected abstract suspend fun prepareLoadString(vertx: Vertx): Map<String, String>

  /**
   * Put some [Variable]s into the persistent map that can then be loaded by
   * another instance. Return these entries.
   */
  protected abstract suspend fun prepareLoadVariable(vertx: Vertx): Map<String, Variable>

  /**
   * Verify that the persistent map has a given size
   */
  protected abstract suspend fun verifySize(vertx: Vertx, expectedSize: Int)

  /**
   * Verify that the back-end contains the given values after the expectedMap has been
   * persisted
   */
  protected abstract suspend fun <V> verifyPersist(vertx: Vertx,
      expectedMap: Map<String, V>, expectedSize: Int = expectedMap.size)

  /**
   * Load the map and verify that it matches the [expectedMap]
   */
  private suspend inline fun <reified V> verifyLoad(expectedMap: Map<String, V>,
      ctx: VertxTestContext) {
    val m = createMap<V>(PERSISTENT_MAP_NAME)
    ctx.verify {
      Assertions.assertThat(m).hasSize(expectedMap.size)
      for (e in expectedMap) {
        Assertions.assertThat(m).contains(e)
      }
    }
  }

  /**
   * Add the given values to the map and then test if they have been correctly persisted
   */
  private suspend inline fun <reified V> persist(values: Map<String, V>, vertx: Vertx,
      ctx: VertxTestContext, expectSizeBefore: Int = 0, expectedSizeAfter: Int = values.size) {
    val m = createMap<V>(PERSISTENT_MAP_NAME)
    for ((k, v) in values) {
      m[k] = v
    }
    ctx.coVerify {
      assertThat(m).hasSize(expectedSizeAfter)
      verifySize(vertx, expectSizeBefore)
      m.persist()
      verifyPersist(vertx, values, expectedSizeAfter)
    }
  }

  /**
   * Test if strings can be loaded
   */
  @Test
  fun loadString(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch {
      verifyLoad(prepareLoadString(vertx), ctx)
      ctx.completeNow()
    }
  }

  /**
   * Test if variables can be loaded
   */
  @Test
  fun loadVariable(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch {
      verifyLoad(prepareLoadVariable(vertx), ctx)
      ctx.completeNow()
    }
  }

  /**
   * Persist string values
   */
  @Test
  fun persistString(vertx: Vertx, ctx: VertxTestContext) {
    GlobalScope.launch {
      persist(mapOf("0" to "A", "1" to "B"), vertx, ctx)
      ctx.completeNow()
    }
  }

  /**
   * Persist variable values
   */
  @Test
  fun persistVariable(vertx: Vertx, ctx: VertxTestContext) {
    val v1 = Variable(value = "B")
    val v2 = Variable(value = "C")
    GlobalScope.launch {
      persist(mapOf("0" to v1, "1" to v2), vertx, ctx)
      ctx.completeNow()
    }
  }

  /**
   * Persist values and then load them again
   */
  @Test
  fun persistAndLoad(vertx: Vertx, ctx: VertxTestContext) {
    val m = mapOf("0" to "A", "1" to "B")
    GlobalScope.launch {
      persist(m, vertx, ctx)
      verifyLoad(m, ctx)
      ctx.completeNow()
    }
  }

  /**
   * Put a value into the map and overwrite an existing one
   */
  @Test
  fun put(vertx: Vertx, ctx: VertxTestContext) {
    val m1 = mapOf("0" to "A", "1" to "B")
    val m2 = mapOf("0" to "C")
    GlobalScope.launch {
      persist(m1, vertx, ctx)
      persist(m2, vertx, ctx, 2, 2)
      verifyLoad(mapOf("0" to "C", "1" to "B"), ctx)
      ctx.completeNow()
    }
  }

  /**
   * Put a new value into the map (without overwriting)
   */
  @Test
  fun putNew(vertx: Vertx, ctx: VertxTestContext) {
    val m1 = mapOf("0" to "A", "1" to "B")
    val m2 = mapOf("2" to "C")
    GlobalScope.launch {
      persist(m1, vertx, ctx)
      persist(m2, vertx, ctx, 2, 3)
      verifyLoad(m1 + m2, ctx)
      ctx.completeNow()
    }
  }

  /**
   * Remove an entry from the map
   */
  @Test
  fun remove(vertx: Vertx, ctx: VertxTestContext) {
    val m = mapOf("0" to "A", "1" to "B")
    GlobalScope.launch {
      persist(m, vertx, ctx)
      val pm = createMap<String>(PERSISTENT_MAP_NAME)
      pm.remove("1")
      assertThat(pm).hasSize(1)
      verifyLoad(m, ctx)
      pm.persist()
      verifyLoad(m - "1", ctx)
      ctx.completeNow()
    }
  }

  /**
   * Clear the map
   */
  @Test
  fun clear(vertx: Vertx, ctx: VertxTestContext) {
    val m = mapOf("0" to "A", "1" to "B")
    GlobalScope.launch {
      persist(m, vertx, ctx)
      val pm = createMap<String>(PERSISTENT_MAP_NAME)
      pm.clear()
      assertThat(pm).isEmpty()
      verifyLoad(m, ctx)
      pm.persist()
      verifyLoad(emptyMap<String, String>(), ctx)
      ctx.completeNow()
    }
  }
}
