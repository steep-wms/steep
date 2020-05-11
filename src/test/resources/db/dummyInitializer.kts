import io.vertx.core.Vertx
import io.vertx.kotlin.core.eventbus.sendAwait

suspend fun dummyInitializer(vertx: Vertx) {
  vertx.eventBus().sendAwait<Unit>("DUMMY_INITIALIZER", "")
}
