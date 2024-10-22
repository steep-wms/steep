import model.plugins.CapabilityMatcherParams

fun dummyCapabilityMatcher(params: CapabilityMatcherParams): Int {
  return if (params.providedCapabilities.contains(params.subject)) {
    1
  } else {
    -1
  }
}
