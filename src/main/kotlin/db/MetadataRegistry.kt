package db

import model.metadata.Service

/**
 * A registry containing all service metadata
 * @author Michel Kraemer
 */
interface MetadataRegistry {
  /**
   * Get a list of all service metadata
   * @return the list
   */
  suspend fun findServices(): List<Service>
}
