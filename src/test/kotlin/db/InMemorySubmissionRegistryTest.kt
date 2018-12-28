package db

/**
 * Test for [InMemorySubmissionRegistry]
 * @author Michel Kraemer
 */
class InMemorySubmissionRegistryTest : SubmissionRegistryTest() {
  override val submissionRegistry: SubmissionRegistry = InMemorySubmissionRegistry()
}
