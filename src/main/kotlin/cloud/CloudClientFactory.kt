package cloud

import ConfigConstants.CLOUD_DRIVER
import ConfigConstants.CLOUD_OPENSTACK_AVAILABILITY_ZONE
import ConfigConstants.CLOUD_OPENSTACK_DOMAIN_NAME
import ConfigConstants.CLOUD_OPENSTACK_ENDPOINT
import ConfigConstants.CLOUD_OPENSTACK_KEYPAIR_NAME
import ConfigConstants.CLOUD_OPENSTACK_NETWORK_ID
import ConfigConstants.CLOUD_OPENSTACK_PASSWORD
import ConfigConstants.CLOUD_OPENSTACK_PROJECT_ID
import ConfigConstants.CLOUD_OPENSTACK_PROJECT_NAME
import ConfigConstants.CLOUD_OPENSTACK_SECURITY_GROUPS
import ConfigConstants.CLOUD_OPENSTACK_USERNAME
import ConfigConstants.CLOUD_OPENSTACK_USE_PUBLIC_IP
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray

/**
 * Factory for [CloudClient] instances
 * @author Michel Kraemer
 */
object CloudClientFactory {
  /**
   * Create a new [CloudClient]
   * @param vertx the current Vert.x instance
   * @return the [CloudClient]
   */
  fun create(vertx: Vertx): CloudClient {
    val config = vertx.orCreateContext.config()

    val driver = config.getString(CLOUD_DRIVER) ?:
        throw IllegalStateException("Missing configuration item `$CLOUD_DRIVER'")
    if (driver != "openstack") {
      throw IllegalStateException("Unknown cloud driver: `$driver'")
    }

    val endpoint = config.getString(CLOUD_OPENSTACK_ENDPOINT) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_ENDPOINT'")

    val username = config.getString(CLOUD_OPENSTACK_USERNAME) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_USERNAME'")

    val password = config.getString(CLOUD_OPENSTACK_PASSWORD) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_PASSWORD'")

    val domainName = config.getString(CLOUD_OPENSTACK_DOMAIN_NAME) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_DOMAIN_NAME'")

    val projectId: String? = config.getString(CLOUD_OPENSTACK_PROJECT_ID)
    val projectName: String? = config.getString(CLOUD_OPENSTACK_PROJECT_NAME)
    if (projectId != null && projectName != null) {
      throw IllegalArgumentException("Configuration items " +
          "`$CLOUD_OPENSTACK_PROJECT_ID' and `$CLOUD_OPENSTACK_PROJECT_NAME' " +
          "must not be set at the same time")
    }
    if (projectId == null && projectName == null) {
      throw IllegalArgumentException("Either configuration item " +
          "`$CLOUD_OPENSTACK_PROJECT_ID' or `$CLOUD_OPENSTACK_PROJECT_NAME' " +
          "must be set")
    }

    val availabilityZone = config.getString(CLOUD_OPENSTACK_AVAILABILITY_ZONE) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_AVAILABILITY_ZONE'")

    val networkId = config.getString(CLOUD_OPENSTACK_NETWORK_ID) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_NETWORK_ID'")

    val usePublicIp = config.getBoolean(CLOUD_OPENSTACK_USE_PUBLIC_IP, false)

    val securityGroups = config.getJsonArray(CLOUD_OPENSTACK_SECURITY_GROUPS,
        JsonArray()).map { it.toString() }

    val keypairName = config.getString(CLOUD_OPENSTACK_KEYPAIR_NAME) ?:
        throw IllegalArgumentException("Missing configuration item " +
            "`$CLOUD_OPENSTACK_KEYPAIR_NAME'")

    return OpenStackClient(endpoint, username, password, domainName,
        projectId, projectName, availabilityZone, networkId, usePublicIp,
        securityGroups, keypairName, vertx)
  }
}
