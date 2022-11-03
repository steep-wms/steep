package cloud

import helper.hazelcast.ClusterMap
import io.vertx.core.Vertx
import model.retry.RetryPolicy
import model.setup.Setup
import java.io.Serializable
import java.time.Instant

/**
 * A cluster-wide map that holds [VMCircuitBreaker] objects
 * @author Michel Kraemer
 */
class VMCircuitBreakerMap(
    /**
     * The name of the cluster map
     */
    name: String,

    /**
     * The list of configured setups
     */
    setups: List<Setup>,

    /**
     * The default value for a circuit breaker's retry policy
     */
    private val defaultCreationPolicyRetries: RetryPolicy,

    /**
     * The default value for a circuit breaker's lock-after-retries timeout
     */
    private val defaultCreationPolicyLockAfterRetries: Long,

    /**
     * The Vert.x instance
     */
    vertx: Vertx) {
  /**
   * An entry in the cluster-wide map. Only [performedAttempts] and
   * [lastAttemptTimestamp] are stored. The other attributes of the circuit
   * breaker are taken from the respective [Setup].
   */
  class Entry(
      var performedAttempts: Int = 0,
      var lastAttemptTimestamp: Instant = Instant.now()
  ) : Serializable

  /**
   * Creates a new entry in the map. The operation is serializable so it can
   * be executed on the side of the map owner.
   */
  class NewEntryOp : (String) -> Entry, Serializable {
    override fun invoke(setupId: String): Entry {
      return Entry()
    }
  }

  /**
   * Updates an entry after an attempt (either [success]ful or not) according
   * to the circuit breaker semantics. The operation is serializable so it can
   * be executed on the side of the map owner.
   */
  class AfterAttemptPerformedOp(private val success: Boolean) :
      (String, Entry) -> Entry, Serializable {
    override fun invoke(setupId: String, currentEntry: Entry): Entry {
      return Entry(
          performedAttempts = if (success) 0 else currentEntry.performedAttempts + 1
      )
    }
  }

  private val setups: Map<String, Setup> = setups.associateBy { it.id }
  private val map: ClusterMap<String, Entry> = ClusterMap.create(name, vertx)

  private fun entryToCircuitBreaker(setup: Setup, entry: Entry): VMCircuitBreaker {
    val retryPolicy = setup.creation?.retries ?: defaultCreationPolicyRetries
    val resetTimeout = setup.creation?.lockAfterRetries ?: defaultCreationPolicyLockAfterRetries
    return VMCircuitBreaker(
        retryPolicy = retryPolicy,
        resetTimeout = resetTimeout,
        performedAttempts = entry.performedAttempts,
        lastAttemptTimestamp = entry.lastAttemptTimestamp
    )
  }

  /**
   * Get the circuit breaker for a given [setupId]
   */
  suspend fun get(setupId: String): VMCircuitBreaker? {
    val setup = setups[setupId] ?: return null
    val entry = map.get(setupId) ?: return null
    return entryToCircuitBreaker(setup, entry)
  }

  /**
   * Get all circuit breakers for all known setup IDs
   */
  suspend fun getAllBySetup(): Map<String, VMCircuitBreaker> {
    val entries = map.entries()
    return entries.mapNotNull { e ->
      setups[e.key]?.let { setup ->
        e.key to entryToCircuitBreaker(setup, e.value)
      }
    }.toMap()
  }

  /**
   * Create a new circuit breaker for the given [setup] if it does not exist
   * yet or return the existing one
   */
  suspend fun computeIfAbsent(setup: Setup): VMCircuitBreaker {
    val entry = map.computeIfAbsent(setup.id, NewEntryOp())!!
    return entryToCircuitBreaker(setup, entry)
  }

  /**
   * Update the circuit breaker state for the given [setupId] after an attempt
   * that was either [success]ful or not
   */
  suspend fun afterAttemptPerformed(setupId: String, success: Boolean) {
    map.computeIfPresent(setupId, AfterAttemptPerformedOp(success))
  }
}
