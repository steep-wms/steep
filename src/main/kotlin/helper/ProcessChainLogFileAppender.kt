package helper

import agent.LocalAgent
import ch.qos.logback.core.FileAppender
import java.nio.file.Paths

/**
 * A logback appender that writes process chain logs into a file. The filename
 * is calculated based on the name of a process chain logger, which should
 * contain the process chain ID.
 * @author Michel Kraemer
 */
class ProcessChainLogFileAppender<E> : FileAppender<E>() {
  /**
   * The name of the process chain logger as defined in [LocalAgent]
   */
  var loggerName: String? = null

  /**
   * The base path for all process chain logs.
   * See [ConfigConstants.LOGS_PROCESSCHAINS_PATH].
   */
  var path: String? = null

  /**
   * The number of characters to use for grouping.
   * See [ConfigConstants.LOGS_PROCESSCHAINS_GROUPBYPREFIX].
   */
  var groupByPrefix = 0

  override fun start() {
    val ln = loggerName
    if (ln == null) {
      addError("The `loggerName' property must be set")
      return
    }
    val p = path
    if (p == null) {
      addError("The `path' property must be set")
      return
    }

    val id = ln.substring(LocalAgent.PROCESSCHAIN_LOG_PREFIX.length)
    val filename = "$id.log"

    file = if (groupByPrefix > 0) {
      Paths.get(p, id.substring(0, groupByPrefix), filename).toString()
    } else {
      Paths.get(p, filename).toString()
    }

    super.start()
  }
}
