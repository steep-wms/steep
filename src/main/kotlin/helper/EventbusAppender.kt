package helper

import AddressConstants
import agent.LocalAgent
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
   * The name of the process chain logger as defined in [LocalAgent]
   */
  var loggerName: String? = null

  private var address: String? = null

  override fun start() {
    if (encoder == null) {
      addError("Missing `encoder'")
      return
    }

    val ln = loggerName
    if (ln == null) {
      addError("Missing `loggerName'")
      return
    }

    val id = ln.substring(LocalAgent.PROCESSCHAIN_LOG_PREFIX.length)
    address = AddressConstants.LOGS_PROCESSCHAINS_PREFIX + id

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
