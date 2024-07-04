package helper.hazelcast

import com.hazelcast.config.ConfigAccessor
import com.hazelcast.instance.impl.DefaultNodeContext
import com.hazelcast.instance.impl.Node
import com.hazelcast.internal.cluster.Joiner
import com.hazelcast.internal.config.AliasedDiscoveryConfigUtils
import com.hazelcast.spi.properties.ClusterProperty

/**
 * A custom node context. Creates a [CombinedJoiner] if the Kubernetes discovery
 * service is enabled. Delegates to the default node context otherwise.
 * @author Michel Kraemer
 */
class CombinedNodeContext(private val kubernetesEnabled: Boolean) : DefaultNodeContext() {
  override fun createJoiner(node: Node): Joiner {
    if (kubernetesEnabled) {
      val join = ConfigAccessor.getActiveMemberNetworkConfig(node.config).join
      join.verify()

      val usePublicAddress = node.properties.getBoolean(ClusterProperty.DISCOVERY_SPI_PUBLIC_IP_ENABLED) ||
          AliasedDiscoveryConfigUtils.allUsePublicAddress(AliasedDiscoveryConfigUtils.aliasedDiscoveryConfigsFrom(join))
      return CombinedJoiner(node, node.discoveryService, usePublicAddress)
    } else {
      return super.createJoiner(node)
    }
  }
}
