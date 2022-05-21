package helper

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.mitchellbosecke.pebble.PebbleEngine
import com.mitchellbosecke.pebble.loader.ClasspathLoader
import com.mitchellbosecke.pebble.loader.DelegatingLoader
import com.mitchellbosecke.pebble.loader.FileLoader
import com.mitchellbosecke.pebble.loader.StringLoader
import org.apache.commons.lang3.BooleanUtils
import org.yaml.snakeyaml.Yaml
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter

/**
 * Utility functions to manipulate YAML objects and arrays
 * @author Michel Kraemer
 */
object YamlUtils {
  val mapper: ObjectMapper = ObjectMapper(YAMLFactory()
        // behave like JsonUtils when serializing type IDs
        .disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID)
      )
      .registerKotlinModule()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)

  /**
   * Convenience method calling [ObjectMapper.readValue]
   */
  inline fun <reified T> readValue(content: String) = mapper.readValue<T>(content)
}

/**
 * Parse a [yaml] string to an object but first apply a template engine with
 * the given [context] if necessary. The template engine will only be applied
 * if the given YAML string contains front matter where the attribute
 * `template` is set to one of `true`, `yes`, `on`, `y`, or `t`. Otherwise, the
 * string will be parsed as is.
 *
 * Example with template engine enabled:
 *
 *     ---
 *     template: true
 *     ---
 *
 *     person:
 *       name: {{ name }}
 */
fun <T> Yaml.loadTemplate(yaml: String, context: Map<String, Any>): T {
  // try to extract front matter
  val frontMatterRegex = """\s*^[-]{3,}\R(.*)\R^[-]{3,}(.*)$""".toRegex(
    setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

  val mr = frontMatterRegex.matchEntire(yaml)
  val strToParse = if (mr != null) {
    // parse front matter
    val frontMatter = load<Map<String, Any>>(mr.groups[1]!!.value)
    val remainder = mr.groups[2]!!.value

    if (BooleanUtils.toBoolean(frontMatter["template"]?.toString())) {
      // apply template engine to yaml string
      val rootTemplateName = "__root__"
      val loader = DelegatingLoader(listOf(object : StringLoader() {
        override fun getReader(templateName: String): Reader? {
          return if (templateName === rootTemplateName) {
            StringReader(remainder)
          } else {
            null
          }
        }
      }, ClasspathLoader(), FileLoader()))

      val engine = PebbleEngine.Builder()
        .strictVariables(true)
        .newLineTrimming(false)
        .loader(loader)
        .build()

      val compiledTemplate = engine.getTemplate(rootTemplateName)
      val writer = StringWriter()
      compiledTemplate.evaluate(writer, context)

      writer.toString()
    } else {
      remainder
    }
  } else {
    yaml
  }

  return load(strToParse)
}
