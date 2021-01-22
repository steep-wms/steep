package helper

import AddressConstants
import agent.LocalAgent
import ch.qos.logback.core.PropertyDefinerBase

/**
 * A logback property definer that takes the name of a process chain logger,
 * extracts the process chain ID and generates an event bus address to which
 * live log messages can be sent
 * @author Michel Kraemer
 */
class ProcessChainLogAddressPropertyDefiner : PropertyDefinerBase() {
  /**
   * The name of the process chain logger as defined in [LocalAgent]
   */
  var loggerName: String? = null

  override fun getPropertyValue(): String? {
    val ln = loggerName
    if (ln == null) {
      addError("The `loggerName' property must be set")
      return null
    }

    val id = ln.substring(LocalAgent.PROCESSCHAIN_LOG_PREFIX.length)
    return AddressConstants.LOGS_PROCESSCHAINS_PREFIX + id
  }
}
