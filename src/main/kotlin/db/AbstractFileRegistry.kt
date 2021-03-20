package db

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import helper.JsonUtils
import helper.YamlUtils
import helper.glob
import io.vertx.core.Vertx
import io.vertx.kotlin.core.executeBlockingAwait
import io.vertx.kotlin.core.file.readFileAwait
import org.yaml.snakeyaml.Yaml

/**
 * Abstract base class for registries that read information from JSON or YAML
 * files using globs.
 * @author Michel Kraemer
 */
abstract class AbstractFileRegistry {
  /**
   * Reads the information from the JSON or YAML files
   * @param paths the paths/globs to the JSON or YAML files
   * @param vertx the Vert.x instance
   * @return the information
   */
  protected suspend inline fun <reified I, reified T : List<I>> find(
      paths: List<String>, vertx: Vertx): List<I> {
    if (paths.isEmpty()) {
      return emptyList()
    }

    val files = vertx.executeBlockingAwait<List<String>> { promise ->
      promise.complete(glob(paths))
    } ?: emptyList()

    // We need this here to get access to T.
    // com.fasterxml.jackson.module.kotlin.readValue does not work inside
    // the flatMap
    val tr = jacksonTypeRef<T>()

    return files.flatMap { file ->
      val content = vertx.fileSystem().readFileAwait(file).toString()
      if (file.toLowerCase().endsWith(".json")) {
        JsonUtils.mapper.readValue(content, tr)
      } else {
        // Use SnakeYAML to parse file and then Jackson to convert it to an
        // object. This is a workaround for jackson-dataformats-text bug #98:
        // https://github.com/FasterXML/jackson-dataformats-text/issues/98
        val yaml = Yaml()
        val l = yaml.load<List<Any>>(content)
        YamlUtils.mapper.convertValue(l, tr)
      }
    }
  }
}
