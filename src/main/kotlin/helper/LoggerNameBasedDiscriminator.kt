package helper

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.sift.Discriminator

/**
 * A logback discriminator based on the logger's name. Can be used together
 * with a [ch.qos.logback.classic.sift.SiftingAppender] to redirect messages
 * of different loggers to separate files.
 * @author Michel Kraemer
 */
class LoggerNameBasedDiscriminator : Discriminator<ILoggingEvent> {
  companion object {
    const val KEY = "loggerName"
  }

  private var started = false

  override fun start() {
    started = true
  }

  override fun stop() {
    started = false
  }

  override fun isStarted() = started

  override fun getKey() = KEY

  override fun getDiscriminatingValue(e: ILoggingEvent?) = e?.loggerName
}
