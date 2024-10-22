package helper

/**
 * Matches required with provided capabilities. Matching can be as simple as
 * checking if all strings in the collection of required capabilities are in
 * the collection of provided ones, or more complex if there are any
 * [CapabilityMatcherPlugin]s doing custom comparisons.
 * @author Michel Kraemer
 */
class CapabilityMatcher {
  /**
   * Matches a collection of [requiredCapabilities] with a collection of
   * [providedCapabilities]. Returns `true` if the collection of provided
   * capabilities satisfies the required ones.
   *
   * If there are [CapabilityMatcherPlugin]s, this function first calls
   * them to check if the capabilities match or not. If there are no plugins
   * or if there was no definitive decision, the function falls back to checking
   * if all strings in the collection of required capabilities are contained in
   * the collection of provided ones.
   */
  fun matches(requiredCapabilities: Collection<String>,
      providedCapabilities: Collection<String>): Boolean {
    return providedCapabilities.containsAll(requiredCapabilities)
  }
}
