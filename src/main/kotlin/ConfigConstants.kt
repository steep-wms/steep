/**
 * Configuration constants
 * @author Michel Kraemer
 */
object ConfigConstants {
  /**
   * `true` if the JobManager verticle should be deployed
   */
  const val JOBMANAGER_ENABLED = "jobmanager.enabled"

  /**
   * The host to bind the JobManager's HTTP server to
   */
  const val JOBMANAGER_HOST = "jobmanager.host"

  /**
   * The port the JobManager's HTTP server should listen on
   */
  const val JOBMANAGER_PORT = "jobmanager.port"

  /**
   * The interval in which the process chain manager looks for registered
   * process chains
   */
  const val PCM_LOOKUP_INTERVAL = "jobmanager.processChainManager.lookupIntervalMilliseconds"

  /**
   * `true` if the agent verticle should be deployed
   */
  const val AGENT_ENABLED = "agent.enabled"

  /**
   * The host to bind the Agent's HTTP server to
   */
  const val AGENT_HOST = "agent.host"

  /**
   * The port the Agent's HTTP server should listen on
   */
  const val AGENT_PORT = "agent.port"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
