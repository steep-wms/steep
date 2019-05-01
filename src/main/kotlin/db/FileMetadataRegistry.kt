package db

import io.vertx.core.Vertx
import model.metadata.Service

/**
 * A metadata registry that reads service metadata from JSON or YAML files
 * @param paths the paths or globs to the JSON or YAML files
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class FileMetadataRegistry(private val paths: List<String>, private val vertx: Vertx) :
    MetadataRegistry, AbstractFileRegistry() {
  private var services: List<Service>? = null

  override suspend fun findServices(): List<Service> {
    if (services == null) {
      services = find(paths, vertx)
    }
    return services!!
  }
}
