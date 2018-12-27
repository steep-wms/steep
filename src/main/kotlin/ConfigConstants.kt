/**
 * Configuration constants
 * @author Michel Kraemer
 */
object ConfigConstants {
  /**
   * The interval in which the process chain manager looks for registered
   * process chains
   */
  const val PCM_LOOKUP_NEXT_PROCESSCHAIN_INTERVAL_MILLISECONDS =
      "jobmanager.processChainManager.lookupNextProcessChainIntervalMilliseconds"
}
