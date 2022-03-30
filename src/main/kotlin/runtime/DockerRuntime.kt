package runtime

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
  private val additionalDockerEnvironment: List<String> = config.getJsonArray(
      ConfigConstants.RUNTIMES_DOCKER_ENV, JsonArray()).map { it.toString() }
  private val additionalDockerVolumes: List<String> = config.getJsonArray(
      ConfigConstants.RUNTIMES_DOCKER_VOLUMES, JsonArray()).map { it.toString() }
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

    // Keep the container name if already defined by the user.
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

    val dockerArgs = listOf(
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerRun", "run"),
            type = Argument.Type.INPUT)
    ) + executable.runtimeArgs + additionalEnvironment + additionalVolumes + containerNameArgument + listOf(
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
}
