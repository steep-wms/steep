package model.metadata

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Service metadata
 * @param id a unique service identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param path relative path to the service executable in the service artifact
 * @param runtime the runtime environment
 * @param parameters list of parameters
 * @param requiredCapabilities a set of capabilities this service needs the
 * host system to have to be able to run
 * @author Michel Kraemer
 */
data class Service(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val runtime: Runtime,
    val parameters: List<ServiceParameter>,
    @JsonProperty("required_capabilities") val requiredCapabilities: Set<String> = emptySet()
) {
  /**
   * The list of currently supported runtime environments
   */
  enum class Runtime(@JsonValue val runtime: String) {
    DOCKER("docker"),
    OTHER("other")
  }
}
