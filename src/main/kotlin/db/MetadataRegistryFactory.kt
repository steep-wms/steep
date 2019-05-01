package db

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray

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
    val paths = vertx.orCreateContext.config().getValue(
        ConfigConstants.SERVICES) ?: throw IllegalStateException(
        "Missing configuration item `${ConfigConstants.SERVICES}'")
    val pathList = when (paths) {
      is JsonArray -> paths.list.map { it as String }
      is String -> listOf(paths)
      else -> throw IllegalStateException("Configuration item " +
          "`${ConfigConstants.SERVICES}' must either be a string or an array")
    }
    return FileMetadataRegistry(pathList, vertx)
  }
}
