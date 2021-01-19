package helper

import org.slf4j.Logger

/**
 * An [OutputCollector] that delegates output to a [delegate] but also
 * logs every line collected to the given [logger]
 * @author Michel Kraemer
 */
class LoggingOutputCollector(private val delegate: OutputCollector,
    private val logger: Logger) : OutputCollector by delegate {
  override fun collect(line: String) {
    logger.info(line)
    delegate.collect(line)
  }
}
