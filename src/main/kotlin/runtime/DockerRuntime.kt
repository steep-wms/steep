package runtime

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

  override fun execute(executable: Executable, outputLinesToCollect: Int): String {
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

    val dockerArgs = listOf(
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerRun", "run"),
            type = Argument.Type.INPUT)
    ) + executable.runtimeArgs + additionalEnvironment + additionalVolumes + listOf(
        Argument(id = UniqueID.next(),
            label = "-v", variable = ArgumentVariable("dockerMount", "$tmpPath:$tmpPath"),
            type = Argument.Type.INPUT),
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerImage", executable.path),
            type = Argument.Type.INPUT)
    )

    val dockerExec = Executable(id = executable.id, path = "docker",
        arguments = dockerArgs + executable.arguments)
    return super.execute(dockerExec, outputLinesToCollect)
  }
}
