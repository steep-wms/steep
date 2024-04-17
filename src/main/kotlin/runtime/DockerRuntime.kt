package runtime

import ConfigConstants
import helper.OutputCollector
import helper.Shell
import helper.UniqueID
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable

/**
 * Runs executables as Docker containers. Uses the executable's path as the
 * Docker image name.
 * @author Michel Kraemer
 */
class DockerRuntime(config: JsonObject) : OtherRuntime() {
  companion object {
    private const val DEFAULT_PULL_AUTO: String = "auto"

    // The following block of patterns is based on the Distribution Project
    // from the Cloud Native Computing Foundation (CNCF), released under the
    // Apache 2.0 license
    // https://github.com/distribution/reference/blob/ff14fafe2236e51c2894ac07d4bdfc778e96d682/reference.go
    private const val DIGEST_ALGORITHM_COMPONENT = """[A-Za-z][A-Za-z0-9]*"""
    private const val DIGEST_ALGORITHM_SEPARATOR = """[+.-_]"""
    private const val DIGEST_ALGORITHM = DIGEST_ALGORITHM_COMPONENT +
        "(?:$DIGEST_ALGORITHM_SEPARATOR$DIGEST_ALGORITHM_COMPONENT)*"
    private const val DIGEST_HEX = """[0-9a-fA-F]{32,}"""
    private const val DIGEST = "$DIGEST_ALGORITHM:$DIGEST_HEX"
    private const val TAG = """\w[\w.-]{0,127}"""

    // an image name that ends with a digest
    private val ENDS_WITH_DIGEST_REGEX = "@$DIGEST$".toRegex()

    // An image name that ends with either a digest or a tag and an optional
    // digest. We have to match against the digest first since any digest
    // (without the algorithm name) is also a valid tag.
    private val TAG_REGEX = "(@$DIGEST)$|:($TAG)(@$DIGEST)?$".toRegex()
  }

  private val additionalDockerEnvironment: List<String> = config.getJsonArray(
      ConfigConstants.RUNTIMES_DOCKER_ENV, JsonArray()).map { it.toString() }
  private val additionalDockerVolumes: List<String> = config.getJsonArray(
      ConfigConstants.RUNTIMES_DOCKER_VOLUMES, JsonArray()).map { it.toString() }
  private val defaultPull: String = config.getString(
      ConfigConstants.RUNTIMES_DOCKER_PULL, DEFAULT_PULL_AUTO)
  private val tmpPath: String = config.getString(ConfigConstants.TMP_PATH) ?:
      throw IllegalStateException("Missing configuration item `${ConfigConstants.TMP_PATH}'")

  override fun execute(executable: Executable, outputCollector: OutputCollector) {
    val additionalEnvironment = additionalDockerEnvironment.map {
      Argument(id = UniqueID.next(),
          label = "-e", variable = ArgumentVariable(UniqueID.next(), it),
          type = Argument.Type.INPUT)
    }
    val additionalVolumes = additionalDockerVolumes.map {
      Argument(id = UniqueID.next(),
          label = "-v", variable = ArgumentVariable(UniqueID.next(), it),
          type = Argument.Type.INPUT)
    }

    // keep container name if already defined
    val existingContainerName = executable.runtimeArgs.firstOrNull { it.label == "--name" }?.variable?.value
    val containerName = existingContainerName ?: "steep-${executable.id}-${executable.serviceId}-${UniqueID.next()}"
      .lowercase().replace("""[^a-z0-9]""".toRegex(), "-")
    val containerNameArgument = if (existingContainerName == null) {
      listOf(Argument(id = UniqueID.next(),
        label = "--name", variable = ArgumentVariable("dockerContainerName", containerName),
        type = Argument.Type.INPUT))
    } else {
      emptyList()
    }

    // decide whether we need to pull the image or not
    var pull = executable.runtimeArgs.firstOrNull { it.label == "--pull" }?.variable?.value ?: defaultPull
    if (pull != "auto" && pull != "always" && pull != "missing" && pull != "never") {
      throw IllegalArgumentException("Invalid value for `pull' argument: `$pull'. " +
          "Possible values: `auto', `always', `missing', `never'.")
    }
    if (pull == DEFAULT_PULL_AUTO) {
      val tag = getTag(executable.path)
      pull = if (hasDigest(executable.path) || (tag != null && tag != "latest")) {
        "missing"
      } else {
        "always"
      }
    }
    val runtimeArgsWithPull = executable.runtimeArgs.filter { it.label != "--pull" } + Argument(
        id = UniqueID.next(), label = "--pull",
        variable = ArgumentVariable(UniqueID.next(), pull),
        type = Argument.Type.INPUT
    )

    val dockerArgs = listOf(
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerRun", "run"),
            type = Argument.Type.INPUT)
    ) + runtimeArgsWithPull + additionalEnvironment + additionalVolumes + containerNameArgument + listOf(
        Argument(id = UniqueID.next(),
            label = "-v", variable = ArgumentVariable("dockerMount", "$tmpPath:$tmpPath"),
            type = Argument.Type.INPUT),
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerImage", executable.path),
            type = Argument.Type.INPUT)
    )

    val dockerExec = Executable(id = executable.id, path = "docker",
        serviceId = executable.serviceId, arguments = dockerArgs + executable.arguments)
    try {
      super.execute(dockerExec, outputCollector)
    } catch (e: InterruptedException) {
      try {
        Shell.execute(listOf("docker", "kill", containerName), outputCollector)
      } catch (t: Throwable) {
        // ignore
      }
      throw e
    }
  }

  /**
   * Check if a Docker [imageName] contains a digest
   */
  private fun hasDigest(imageName: String): Boolean =
      ENDS_WITH_DIGEST_REGEX.containsMatchIn(imageName)

  /**
   * Get the tag from a Docker [imageName] or `null` if it does not
   * contain a tag
   */
  private fun getTag(imageName: String): String? =
     TAG_REGEX.find(imageName)?.let { it.groupValues[2].ifEmpty { null } }
}
