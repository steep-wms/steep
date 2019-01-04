package db

import io.vertx.core.Vertx
import java.lang.IllegalStateException

/**
 * Creates [MetadataRegistry] objects
 * @author Michel Kraemer
 */
object MetadataRegistryFactory {
  /**
   * Create a new [MetadataRegistry]
   * @param vertx the current Vert.x instance
   * @return the [MetadataRegistry]
   */
  fun create(vertx: Vertx): MetadataRegistry {
    val path = vertx.orCreateContext.config().getString(
        ConfigConstants.SERVICE_METADATA_FILE) ?: throw IllegalStateException(
        "Missing configuration item `" + ConfigConstants.SERVICE_METADATA_FILE + "'")
    return FileMetadataRegistry(path)
  }
}
