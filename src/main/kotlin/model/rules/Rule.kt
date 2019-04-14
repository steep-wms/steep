package model.rules

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

/**
 * A rule that applies an action if its condition is met
 * @author Michel Kraemer
 */
data class Rule(
    val name: String,
    val target: Target,
    @JsonProperty("when") val condition: String,
    @JsonProperty("then") val action: String
) {
  enum class Target(@JsonValue val target: String) {
    PROCESSCHAIN("processChain"),
    EXECUTABLE("executable")
  }
}
