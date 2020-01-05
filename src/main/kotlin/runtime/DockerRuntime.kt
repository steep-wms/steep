package runtime

import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable

/**
 * Runs executables as Docker containers. Uses the executable's path as the
 * Docker image name.
 * @author Michel Kraemer
 */
class DockerRuntime(vertx: Vertx) : OtherRuntime() {
  private val additionalDockerVolumes: List<String>
  private val tmpPath: String

  init {
    val config = vertx.orCreateContext.config()
    additionalDockerVolumes = config.getJsonArray(
        ConfigConstants.RUNTIMES_DOCKER_VOLUMES, JsonArray()).map { it.toString() }
    tmpPath = config.getString(ConfigConstants.TMP_PATH) ?: throw IllegalStateException(
        "Missing configuration item `${ConfigConstants.TMP_PATH}'")
  }

  override fun execute(executable: Executable, outputLinesToCollect: Int): String {
    val additionalVolumes = additionalDockerVolumes.map {
      Argument(id = UniqueID.next(),
          label = "-v", variable = ArgumentVariable(UniqueID.next(), it),
          type = Argument.Type.ARGUMENT)
    }

    val dockerArgs = listOf(
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerRun", "run"),
            type = Argument.Type.ARGUMENT)
    ) + executable.runtimeArgs + additionalVolumes + listOf(
        Argument(id = UniqueID.next(),
            label = "-v", variable = ArgumentVariable("dockerMount", "$tmpPath:$tmpPath"),
            type = Argument.Type.ARGUMENT),
        Argument(id = UniqueID.next(),
            variable = ArgumentVariable("dockerImage", executable.path),
            type = Argument.Type.ARGUMENT)
    )

    val dockerExec = Executable(id = executable.id, path = "docker",
        arguments = dockerArgs + executable.arguments)
    return super.execute(dockerExec, outputLinesToCollect)
  }
}
