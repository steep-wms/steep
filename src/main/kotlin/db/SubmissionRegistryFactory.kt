package db

import io.vertx.core.Vertx

/**
 * Creates [SubmissionRegistry] objects
 * @author Michel Kraemer
 */
object SubmissionRegistryFactory {
  /**
   * Create a new [SubmissionRegistry]
   * @param vertx the current Vert.x instance
   * @return the [SubmissionRegistry]
   */
  fun create(vertx: Vertx): SubmissionRegistry = InMemorySubmissionRegistry(vertx)
}
