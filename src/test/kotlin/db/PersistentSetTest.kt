package db

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.workflow.Action
import model.workflow.ExecuteAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Tests for [PersistentSet]
 */
@ExtendWith(VertxExtension::class)
class PersistentSetTest {
  companion object {
    const val PERSISTENT_SET_NAME = "PersistentSet"
  }

  /**
   * Create a persistent map
   */
  private fun createMap(vertx: Vertx): PersistentMap<Action, Boolean> =
      InMemoryPersistentMap(PERSISTENT_SET_NAME,
          { JsonUtils.mapper.writeValueAsString(it) },
          { JsonUtils.mapper.readValue<Action>(it) },
          { it.toString() }, { it.toBoolean() }, vertx)

  /**
   * Create a persistent set
   */
  private fun createSet(vertx: Vertx): PersistentSet<Action> =
      PersistentSet(createMap(vertx))

  /**
   * Load a persistent set
   */
  @Test
  fun load(vertx: Vertx, ctx: VertxTestContext) {
    val a1 = ExecuteAction("A")
    val a2 = ExecuteAction("B")
    val m = createMap(vertx)
    m[a1] = true
    m[a2] = true

    GlobalScope.launch {
      m.persist()

      val s = createSet(vertx)
      s.load()

      ctx.verify {
        assertThat(s).hasSize(2).contains(a1).contains(a2)
      }

      ctx.completeNow()
    }
  }

  /**
   * Persist a persistent set
   */
  @Test
  fun persist(vertx: Vertx, ctx: VertxTestContext) {
    val a1 = ExecuteAction("A")
    val a2 = ExecuteAction("B")
    val s = createSet(vertx)
    s.add(a1)
    s.add(a2)

    GlobalScope.launch {
      s.persist()

      val m = createMap(vertx)
      m.load()

      ctx.verify {
        assertThat(m).hasSize(2).contains(entry(a1, true)).contains(entry(a2, true))
      }

      ctx.completeNow()
    }
  }
}
