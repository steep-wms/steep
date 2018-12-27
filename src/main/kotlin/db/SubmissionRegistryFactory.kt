package db

/**
 * Creates [SubmissionRegistry] objects
 * @author Michel Kraemer
 */
object SubmissionRegistryFactory {
  /**
   * Create a new [SubmissionRegistry]
   * @return the [SubmissionRegistry]
   */
  fun create(): SubmissionRegistry = InMemorySubmissionRegistry()
}
