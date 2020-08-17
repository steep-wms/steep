import io.vertx.core.Vertx
import io.vertx.kotlin.core.eventbus.requestAwait

suspend fun dummyInitializer(vertx: Vertx) {
  vertx.eventBus().requestAwait<Unit>("DUMMY_INITIALIZER", "")
}
