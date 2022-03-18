import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.await

suspend fun dummyInitializer(vertx: Vertx) {
  vertx.eventBus().request<Unit>("DUMMY_INITIALIZER", "").await()
}
