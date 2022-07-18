package runtime

import ConfigConstants
import helper.DefaultOutputCollector
import helper.Shell
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.core.json.array
import io.vertx.kotlin.core.json.json
import io.vertx.kotlin.core.json.obj
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import model.metadata.Service
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Tests for [DockerRuntime]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class DockerRuntimeTest {
  companion object {
    private const val EXPECTED = "Elvis"

    @BeforeAll
    @JvmStatic
    fun setUp() {
      // Make sure the alpine:latest Docker image exists on the system.
      // Otherwise, the image will be pulled on demand and the output
      // collectors in the unit tests below will collect the output of
      // "docker pull" too, which will make the tests fail.
      Shell.execute(listOf("docker", "pull", "alpine:latest"))
    }
  }

  /**
   * Test that the runtime fails if [ConfigConstants.TMP_PATH] is not configured
   */
  @Test
  fun missingConf(ctx: VertxTestContext) {
    ctx.verify {
      assertThatThrownBy {
        DockerRuntime(JsonObject())
      }.isInstanceOf(IllegalStateException::class.java)
    }
    ctx.completeNow()
  }

  /**
   * Test that a simple Docker container can be executed and that its output
   * can be collected
   */
  @Test
  fun executeEcho(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString()
      )
    }

    val exec = Executable(path = "alpine", serviceId = "echo", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.verify {
        val collector = DefaultOutputCollector()
        runtime.execute(exec, collector)
        assertThat(collector.output()).isEqualTo(EXPECTED)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that [ConfigConstants.TMP_PATH] is correctly mounted
   */
  @Test
  fun executeTmpPath(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val f = File(tempDir.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString()
      )
    }

    val exec = Executable(path = "alpine", serviceId = "cat", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "cat"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), f.absolutePath),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.verify {
        val collector = DefaultOutputCollector()
        runtime.execute(exec, collector)
        assertThat(collector.output()).isEqualTo(EXPECTED)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a Docker container can be executed with a mounted volume
   */
  @Test
  fun executeVolume(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path,
      @TempDir tempDir2: Path) {
    val f = File(tempDir2.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString()
      )
    }

    val containerFileName = "/tmp/test.txt"
    val exec = Executable(path = "alpine", serviceId = "cat", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "cat"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), containerFileName),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER, runtimeArgs = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "-v"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(),
            "${f.absolutePath}:$containerFileName"),
            type = Argument.Type.INPUT)
    ))

    val runtime = DockerRuntime(config)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.verify {
        val collector = DefaultOutputCollector()
        runtime.execute(exec, collector)
        assertThat(collector.output()).isEqualTo(EXPECTED)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a Docker container can be executed with an environment variable
   */
  @Test
  fun executeEnv(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString()
      )
    }

    val exec = Executable(path = "alpine", serviceId = "sh", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sh"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "-c"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo \$MYVAR"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER, runtimeArgs = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "-e"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "MYVAR=$EXPECTED"),
            type = Argument.Type.INPUT)
    ))

    val runtime = DockerRuntime(config)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.verify {
        val collector = DefaultOutputCollector()
        runtime.execute(exec, collector)
        assertThat(collector.output()).isEqualTo(EXPECTED)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a Docker container can be executed with a mounted volume specified
   * in the configuration object
   */
  @Test
  fun executeVolumeConf(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path,
      @TempDir tempDir2: Path) {
    val f = File(tempDir2.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val containerFileName = "/tmp/test.txt"
    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString(),
          ConfigConstants.RUNTIMES_DOCKER_VOLUMES to array(
              "${f.absolutePath}:$containerFileName"
          )
      )
    }

    val exec = Executable(path = "alpine", serviceId = "cat", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "cat"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), containerFileName),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.verify {
        val collector = DefaultOutputCollector()
        runtime.execute(exec, collector)
        assertThat(collector.output()).isEqualTo(EXPECTED)
      }
      ctx.completeNow()
    }
  }

  /**
   * Test that a Docker container can be executed with an environment variable
   * specified in the configuration object
   */
  @Test
  fun executeEnvConf(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString(),
          ConfigConstants.RUNTIMES_DOCKER_ENV to array(
              "MYVAR=$EXPECTED"
          )
      )
    }

    val exec = Executable(path = "alpine", serviceId = "sh", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sh"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "-c"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo \$MYVAR"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    CoroutineScope(vertx.dispatcher()).launch {
      ctx.verify {
        val collector = DefaultOutputCollector()
        runtime.execute(exec, collector)
        assertThat(collector.output()).isEqualTo(EXPECTED)
      }
      ctx.completeNow()
    }
  }

  /**
   * Get all running docker containers
   */
  private fun getContainerNames(): List<String> {
    val outputCollector = DefaultOutputCollector()
    Shell.execute(listOf("docker", "container", "ls", "--format={{.Names}}"),
        outputCollector)
    return outputCollector.lines()
  }

  /**
   * Make sure a docker container is actually killed when the executable is cancelled
   */
  @Test
  fun killContainerOnCancel(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = json {
      obj(
          ConfigConstants.TMP_PATH to tempDir.toString()
      )
    }

    val exec = Executable(path = "alpine", serviceId = "sleep", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "120"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    // launch a container in the background
    val runtime = DockerRuntime(config)
    val executor = Executors.newSingleThreadExecutor()
    val execFuture = executor.submit {
      runtime.execute(exec, DefaultOutputCollector())
    }

    CoroutineScope(vertx.dispatcher()).launch {
      // wait until the container is there
      while (true) {
        val containers = getContainerNames()
        val sleepContainer = containers.any { it.startsWith("steep-${exec.id}-sleep") }
        if (sleepContainer) {
          break
        }
        delay(1000)
      }

      // cancel container
      execFuture.cancel(true)

      // wait for the container to disappear
      while (true) {
        val containers = getContainerNames()
        val sleepContainerGone = containers.none { it.startsWith("steep-${exec.id}-sleep") }
        if (sleepContainerGone) {
          break
        }
        delay(1000)
      }

      ctx.completeNow()
    }
  }

  /**
   * Test that a given docker container name is not overwritten
   */
  @Test
  fun setContainerName(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val config = json {
      obj(
        ConfigConstants.TMP_PATH to tempDir.toString()
      )
    }

    val containerName = "testing-steep-container-names-" + UniqueID.next()
    val exec = Executable(path = "alpine", serviceId = "sleep",
      runtimeArgs = listOf(
        Argument(id = UniqueID.next(),
          label = "--name", variable = ArgumentVariable("containerName", containerName),
          type = Argument.Type.INPUT)
      ),
      arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sleep"),
          type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "120"),
          type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    // launch a container in the background
    val runtime = DockerRuntime(config)
    val executor = Executors.newSingleThreadExecutor()
    val execFuture = executor.submit {
      runtime.execute(exec, DefaultOutputCollector())
    }

    CoroutineScope(vertx.dispatcher()).launch {
      // wait until the container is there
      while (true) {
        val containers = getContainerNames()
        val sleepContainer = containers.any { it == containerName }
        if (sleepContainer) {
          break
        }
        delay(1000)
      }

      // cancel container
      execFuture.cancel(true)

      // wait for the container to disappear
      while (true) {
        val containers = getContainerNames()
        val sleepContainerGone = containers.none { it == containerName }
        if (sleepContainerGone) {
          break
        }
        delay(1000)
      }

      ctx.completeNow()
    }
  }
}
