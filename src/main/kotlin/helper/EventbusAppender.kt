package helper

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import globalVertxInstance

/**
 * A logback appender that publishes log events to the Vert.x event bus
 * @author Michel Kraemer
 */
class EventbusAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {
  var encoder: PatternLayoutEncoder? = null

  /**
   * The event bus address to which to send the events
   */
  var address: String? = null

  override fun start() {
    if (encoder == null) {
      addError("Missing encoder")
      return
    }
    if (address == null) {
      addError("Missing event bus address")
      return
    }
    super.start()
  }

  override fun append(event: ILoggingEvent?) {
    if (!isStarted) {
      return
    }

    val b = encoder!!.encode(event)
    globalVertxInstance.eventBus().publish(address, String(b))
  }
}
