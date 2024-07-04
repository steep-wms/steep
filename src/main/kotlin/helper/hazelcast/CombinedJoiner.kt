package helper.hazelcast

import com.hazelcast.cluster.Address
import com.hazelcast.config.TcpIpConfig
import com.hazelcast.instance.impl.Node
import com.hazelcast.internal.cluster.impl.DiscoveryJoiner
import com.hazelcast.internal.cluster.impl.TcpIpJoiner
import com.hazelcast.kubernetes.HazelcastKubernetesDiscoveryStrategyFactory
import com.hazelcast.spi.discovery.integration.DiscoveryService

/**
 * A Hazelcast joiner that delegates to both a [DiscoveryJoiner] and a [TcpIpJoiner]:
 *
 * * The [DiscoveryJoiner] is used to dynamically find cluster members through
 *   Hazelcast's discovery SPI (e.g. through [HazelcastKubernetesDiscoveryStrategyFactory])
 * * The [TcpIpJoiner] is used to add static members provided in the [TcpIpConfig]
 *
 * @author Michel Kraemer
 */
class CombinedJoiner(node: Node, discoveryService: DiscoveryService, usePublicAddress: Boolean) :
    DiscoveryJoiner(node, discoveryService, usePublicAddress) {
  private val tcpIpDiscoverer = TcpIpDiscoverer(node)

  override fun getPossibleAddressesForInitialJoin(): Collection<Address> {
    val result = super.getPossibleAddressesForInitialJoin().toMutableList()
    result.addAll(tcpIpDiscoverer.discoverInitialMembers())
    logger.info("Discovered possible addresses for initial join: $result")
    return result
  }

  /**
   * Helper class to access possible addresses for initial join from [TcpIpJoiner]
   */
  private class TcpIpDiscoverer(node: Node) : TcpIpJoiner(node) {
    fun discoverInitialMembers(): Collection<Address> {
      return super.getPossibleAddressesForInitialJoin()
    }
  }
}
