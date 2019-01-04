package helper

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.awaitEvent

suspend fun <T> abortableDelay(timeMillis: Long, abortAddress: String): T? {
  if (timeMillis <= 0) {
    return null
  }
  val vertx = Vertx.currentContext().owner()
  return awaitEvent<T> { handler ->
    val consumer = vertx.eventBus().consumer<T>(abortAddress)
    var timerId = 0L
    var called = false
    val cb = cb@ { t: T? ->
      if (called) {
        return@cb
      }
      called = true
      if (consumer.isRegistered) {
        consumer.unregister()
      }
      if (timerId != 0L) {
        vertx.cancelTimer(timerId)
        timerId = 0L
      }
      handler.handle(t)
    }
    consumer.handler { cb(it.body()) }
    timerId = vertx.setTimer(timeMillis) { cb(null) }
  }
}
