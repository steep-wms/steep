package db

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import helper.YamlUtils
import model.metadata.Service
import java.io.File

/**
 * A metadata registry that reads service metadata from a JSON or YAML file
 * @param path the path to the JSON or YAML file
 * @author Michel Kraemer
 */
class FileMetadataRegistry(path: String) : MetadataRegistry {
  private val services: List<Service> = if (path.toLowerCase().endsWith(".json")) {
    JsonUtils.mapper.readValue(File(path))
  } else {
    YamlUtils.mapper.readValue(File(path))
  }

  override suspend fun findServices(): List<Service> = services
}
