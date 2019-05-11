package model.metadata

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Service metadata
 * @param id a unique service identifier
 * @param name a human-readable name
 * @param description a human-readable description
 * @param path relative path to the service executable in the service artifact
 * @param runtime the runtime environment
 * @param parameters list of parameters
 * @param runtimeArgs optional list of arguments to pass to the runtime
 * @param requiredCapabilities a set of capabilities this service needs the
 * host system to have to be able to run
 * @author Michel Kraemer
 */
data class Service(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val runtime: String,
    val parameters: List<ServiceParameter>,
    @JsonProperty("runtime_args") val runtimeArgs: List<RuntimeArgument> = emptyList(),
    @JsonProperty("required_capabilities") val requiredCapabilities: Set<String> = emptySet()
) {
  companion object {
    /**
     * A list of built-in runtime environments
     */
    const val RUNTIME_DOCKER = "docker"
    const val RUNTIME_OTHER = "other"
  }
}
