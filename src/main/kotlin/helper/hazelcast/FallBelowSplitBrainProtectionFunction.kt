package helper.hazelcast

import com.hazelcast.cluster.Member
import com.hazelcast.splitbrainprotection.SplitBrainProtectionFunction

/**
 * Protects against [split-brain situations](https://docs.hazelcast.com/imdg/4.2/network-partitioning/split-brain-protection).
 *
 * Works similar to [com.hazelcast.splitbrainprotection.impl.MemberCountSplitBrainProtectionFunction]
 * (i.e. it enforces a minimum number of members in the cluster) but only
 * starts to be in effect once the cluster has reached its minimum size.
 *
 * This allows the cluster to startup gracefully even if the member count is
 * temporarily lower than the defined minimum. Once the minimum has been
 * reached, the function becomes active. When the member count then falls below
 * the minimum again, it triggers a split-brain situation.
 *
 * @author Michel Kraemer
 */
class FallBelowSplitBrainProtectionFunction(
    private val splitBrainProtectionSize: Int
) : SplitBrainProtectionFunction {
  private var isActive = false

  override fun apply(members: MutableCollection<Member>): Boolean {
    if (!isActive) {
      if (members.size >= splitBrainProtectionSize) {
        isActive = true
      }
      return true
    }

    return members.size >= splitBrainProtectionSize
  }
}
