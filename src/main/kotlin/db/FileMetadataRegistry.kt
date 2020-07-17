package db

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import helper.JsonUtils
import io.vertx.core.Vertx
import model.metadata.Service
import org.slf4j.LoggerFactory

/**
 * A metadata registry that reads service metadata from JSON or YAML files
 * @param paths the paths or globs to the JSON or YAML files
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class FileMetadataRegistry(private val paths: List<String>, private val vertx: Vertx) :
    MetadataRegistry, AbstractFileRegistry() {
  companion object {
    private val log = LoggerFactory.getLogger(FileMetadataRegistry::class.java)
  }

  private var services: List<Service>? = null

  override suspend fun findServices(): List<Service> {
    if (services == null) {
      // services = find(paths, vertx)
      val json: List<Map<String, Any>> = find(paths, vertx)

      // modify parameters with type "argument"
      convertArgumentsToInputs(json)

      services = JsonUtils.mapper.convertValue(json, jacksonTypeRef<List<Service>>())
    }
    return services!!
  }

  /**
   * Search the given list of [json] objects for service parameters with the
   * type `argument`. Convert the type to `input` and issue a warning.
   */
  private fun convertArgumentsToInputs(json: List<Map<String, Any>>) {
    for (service in json) {
      val parameters = service["parameters"]
      if (parameters is List<*>) {
        for (obj in parameters) {
          @Suppress("UNCHECKED_CAST") val param = obj as MutableMap<String, Any>
          if (param["type"] == "argument") {
            log.warn("Found a parameter with type `argument' in the " +
                "service metadata. Arguments are unnecessary and will be " +
                "removed in Steep 6.0.0. Use the type `input' instead. " +
                "The service metadata will now be modified and parameter " +
                "type will be set to `input'.")
            param["type"] = "input"
          }
        }
      }
    }
  }
}
