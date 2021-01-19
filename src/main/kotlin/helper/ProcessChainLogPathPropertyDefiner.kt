package helper

import ConfigConstants
import agent.LocalAgent
import ch.qos.logback.core.PropertyDefinerBase
import java.nio.file.Paths

/**
 * A logback property definer that takes the name of a process chain logger,
 * extracts the process chain ID and generates a path for the corresponding
 * log file.
 * @author Michel Kraemer
 */
class ProcessChainLogPathPropertyDefiner : PropertyDefinerBase() {
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

  override fun getPropertyValue(): String? {
    val ln = loggerName
    if (ln == null) {
      addError("The `loggerName' property must be set")
      return null
    }
    val p = path
    if (p == null) {
      addError("The `path' property must be set")
      return null
    }

    val id = ln.substring(LocalAgent.PROCESSCHAIN_LOG_PREFIX.length)
    val filename = "$id.log"

    return if (groupByPrefix > 0) {
      Paths.get(p, id.substring(0, groupByPrefix), filename).toString()
    } else {
      Paths.get(p, filename).toString()
    }
  }
}
