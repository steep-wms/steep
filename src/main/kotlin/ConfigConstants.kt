/**
 * Configuration constants
 * @author Michel Kraemer
 */
object ConfigConstants {
  /**
   * `true` the the JobManager should deploy an HTTP server
   */
  const val HTTP_ENABLED = "jobmanager.http.enabled"

  /**
   * The host to bind the JobManager's HTTP server to
   */
  const val HTTP_HOST = "jobmanager.http.host"

  /**
   * The port the JobManager's HTTP server should listen on
   */
  const val HTTP_PORT = "jobmanager.http.port"

  /**
   * The interval in which the scheduler looks for registered process chains
   */
  const val SCHEDULER_LOOKUP_INTERVAL = "jobmanager.scheduler.lookupIntervalMilliseconds"

  /**
   * `true` if this JobManager instance should be able to execute process
   * chains through [agent.LocalAgent]
   */
  const val AGENT_ENABLED = "jobmanager.agent.enabled"

  /**
   * Get all configuration keys from this class
   * @return the list of configuration keys
   */
  fun getConfigKeys(): List<String> = ConfigConstants::class.java.fields
      .map { it.get(null) }
      .filterIsInstance<String>()
}
