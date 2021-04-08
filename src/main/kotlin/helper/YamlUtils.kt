package helper

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Utility functions to manipulate YAML objects and arrays
 * @author Michel Kraemer
 */
object YamlUtils {
  val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
      .registerKotlinModule()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  /**
   * Convenience method calling [ObjectMapper.readValue]
   */
  inline fun <reified T> readValue(content: String) = mapper.readValue<T>(content)
}
