package model.metadata

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import model.processchain.Argument

/**
 * Service runtime arguments
 * @param id a unique argument identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param dataType argument data type
 * @param label argument's name on the command line
 * @param value the argument's value
 * @author Michel Kraemer
 */
data class RuntimeArgument(
    val id: String,
    val name: String,
    val description: String,
    val dataType: String = Argument.DATA_TYPE_STRING,
    val label: String? = null,
    @JsonDeserialize(using = ToStringDeserializer::class)
    val value: String? = null
)

private class ToStringDeserializer : JsonDeserializer<String>() {
  override fun deserialize(p: JsonParser, ctxt: DeserializationContext): String {
    val node: JsonNode = p.codec.readTree(p)
    return when {
      node.isObject || node.isArray -> node.toString()
      else -> node.asText()
    }
  }
}
