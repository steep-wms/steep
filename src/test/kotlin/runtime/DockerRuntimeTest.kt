package runtime

import ConfigConstants
import helper.DefaultOutputCollector
import helper.Shell
import helper.UniqueID
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import model.metadata.Service
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import runtime.ContainerRuntimeTest.Companion.EXPECTED
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Tests for [DockerRuntime]
 * @author Michel Kraemer
 */
class DockerRuntimeTest : ContainerRuntimeTest {
  companion object {
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

  override fun createDefaultConfig(tempDir: Path): JsonObject {
    return jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )
  }

  override fun createRuntime(config: JsonObject): Runtime {
    return DockerRuntime(config)
  }

  /**
   * Test that the runtime fails if [ConfigConstants.TMP_PATH] is not configured
   */
  @Test
  fun missingConf() {
    assertThatThrownBy {
      createRuntime(JsonObject())
    }.isInstanceOf(IllegalStateException::class.java)
  }

  /**
   * Test that [ConfigConstants.TMP_PATH] is correctly mounted
   */
  @Test
  fun executeTmpPath(@TempDir tempDir: Path) {
    val f = File(tempDir.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

    val exec = Executable(path = "alpine", serviceId = "cat", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "cat"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), f.absolutePath),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a Docker container can be executed with a mounted volume
   */
  @Test
  fun executeVolume(@TempDir tempDir: Path, @TempDir tempDir2: Path) {
    val f = File(tempDir2.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

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
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a Docker container can be executed with an environment variable
   */
  @Test
  fun executeEnv(@TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

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
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a Docker container can be executed with a mounted volume specified
   * in the configuration object
   */
  @Test
  fun executeVolumeConf(@TempDir tempDir: Path, @TempDir tempDir2: Path) {
    val f = File(tempDir2.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val containerFileName = "/tmp/test.txt"
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_VOLUMES to jsonArrayOf(
            "${f.absolutePath}:$containerFileName"
        ),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

    val exec = Executable(path = "alpine", serviceId = "cat", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "cat"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), containerFileName),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a Docker container can be executed with an environment variable
   * specified in the configuration object
   */
  @Test
  fun executeEnvConf(@TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_ENV to jsonArrayOf(
            "MYVAR=$EXPECTED"
        ),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

    val exec = Executable(path = "alpine", serviceId = "sh", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sh"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "-c"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo \$MYVAR"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = DockerRuntime(config)
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
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
  fun killContainerOnCancel(@TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

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

    // wait until the container is there
    while (true) {
      val containers = getContainerNames()
      val sleepContainer = containers.any { it.startsWith("steep-${exec.id}-sleep") }
      if (sleepContainer) {
        break
      }
      Thread.sleep(1000)
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
      Thread.sleep(1000)
    }
  }

  /**
   * Test that a given docker container name is not overwritten
   */
  @Test
  fun setContainerName(@TempDir tempDir: Path) {
    val config = jsonObjectOf(
        ConfigConstants.TMP_PATH to tempDir.toString(),
        ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
    )

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

    // wait until the container is there
    while (true) {
      val containers = getContainerNames()
      val sleepContainer = containers.any { it == containerName }
      if (sleepContainer) {
        break
      }
      Thread.sleep(1000)
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
      Thread.sleep(1000)
    }
  }

  @Nested
  inner class Pull {
    @BeforeEach
    fun setUp() {
      mockkObject(Shell)
      every { Shell.execute(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
      unmockkObject(Shell)
    }

    /**
     * Clear recorded calls but not the mock itself so we can call verify()
     * again. Clearing the mock itself would also remove any answer specified
     * with `every { ... } ...`.
     */
    private fun clearRecordedCalls() {
      clearMocks(
          Shell,
          answers = false,
          recordedCalls = true,
          childMocks = false,
          verificationMarks = true,
          exclusionRules = false
      )
    }

    /**
     * Test if we can specify a pull argument
     */
    @Test
    fun static() {
      val config = jsonObjectOf(ConfigConstants.TMP_PATH to "/tmp")
      val rt = DockerRuntime(config)

      for (v in listOf("always", "missing", "never")) {
        rt.execute(
            Executable(
                path = "alpine",
                serviceId = "sleep",
                arguments = emptyList(),
                runtimeArgs = listOf(
                    Argument(
                        label = "--pull",
                        variable = ArgumentVariable(UniqueID.next(), v),
                        type = Argument.Type.INPUT
                    )
                )
            ),
            DefaultOutputCollector()
        )
        verify { Shell.execute(match { it.windowed(2).any { w ->
          w == listOf("--pull", v)
        }}, any(), any(), any()) }
        clearRecordedCalls()
      }
    }

    /**
     * Test if we can specify a default pull value
     */
    @Test
    fun default() {
      for (v in listOf("always", "missing", "never")) {
        val config = jsonObjectOf(
            ConfigConstants.TMP_PATH to "/tmp",
            ConfigConstants.RUNTIMES_DOCKER_PULL to v
        )
        val rt = DockerRuntime(config)
        rt.execute(
            Executable(
                path = "alpine",
                serviceId = "sleep",
                arguments = emptyList(),
            ),
            DefaultOutputCollector()
        )
        verify { Shell.execute(match { it.windowed(2).any { w ->
          w == listOf("--pull", v)
        }}, any(), any(), any()) }
        clearRecordedCalls()
      }
    }

    /**
     * Test if we can override a default pull value
     */
    @Test
    fun override() {
      val config = jsonObjectOf(
          ConfigConstants.TMP_PATH to "/tmp",
          ConfigConstants.RUNTIMES_DOCKER_PULL to "never"
      )
      val rt = DockerRuntime(config)

      for (v in listOf("always", "missing", "never")) {
        rt.execute(
            Executable(
                path = "alpine",
                serviceId = "sleep",
                arguments = emptyList(),
                runtimeArgs = listOf(
                    Argument(
                        label = "--pull",
                        variable = ArgumentVariable(UniqueID.next(), v),
                        type = Argument.Type.INPUT
                    )
                )
            ),
            DefaultOutputCollector()
        )
        verify { Shell.execute(match { it.windowed(2).any { w ->
          w == listOf("--pull", v)
        }}, any(), any(), any()) }
        clearRecordedCalls()
      }
    }

    /**
     * Test if an invalid default value will be rejected
     */
    @Test
    fun invalidDefault() {
      val config = jsonObjectOf(
          ConfigConstants.TMP_PATH to "/tmp",
          ConfigConstants.RUNTIMES_DOCKER_PULL to "invalid"
      )
      val rt = DockerRuntime(config)
      assertThatThrownBy {
        rt.execute(
            Executable(
                path = "alpine",
                serviceId = "sleep",
                arguments = emptyList(),
            ),
            DefaultOutputCollector()
        )
      }.isInstanceOf(IllegalArgumentException::class.java)
          .hasMessageContaining("Invalid value for `pull' argument")
    }

    /**
     * Test if an invalid value will be rejected
     */
    @Test
    fun invalidStatic() {
      val config = jsonObjectOf(ConfigConstants.TMP_PATH to "/tmp")
      val rt = DockerRuntime(config)
      assertThatThrownBy {
        rt.execute(
            Executable(
                path = "alpine",
                serviceId = "sleep",
                arguments = emptyList(),
                runtimeArgs = listOf(
                    Argument(
                        label = "--pull",
                        variable = ArgumentVariable(UniqueID.next(), "invalid"),
                        type = Argument.Type.INPUT
                    )
                )
            ),
            DefaultOutputCollector()
        )
      }.isInstanceOf(IllegalArgumentException::class.java)
          .hasMessageContaining("Invalid value for `pull' argument")
    }

    /**
     * Test if the value can automatically be detected from the image tag
     */
    @Test
    fun auto() {
      val config = jsonObjectOf(ConfigConstants.TMP_PATH to "/tmp")
      val rt = DockerRuntime(config)

      // an image without a tag should always be pulled
      rt.execute(
          Executable(
              path = "alpine",
              serviceId = "sleep",
              arguments = emptyList()
          ),
          DefaultOutputCollector()
      )
      verify { Shell.execute(match { it.windowed(2).any { w ->
        w == listOf("--pull", "always")
      }}, any(), any(), any()) }
      clearRecordedCalls()

      // an image with a tag matching `latest` should always be pulled
      rt.execute(
          Executable(
              path = "alpine:latest",
              serviceId = "sleep",
              arguments = emptyList()
          ),
          DefaultOutputCollector()
      )
      verify { Shell.execute(match { it.windowed(2).any { w ->
        w == listOf("--pull", "always")
      }}, any(), any(), any()) }
      clearRecordedCalls()

      // an image with a tag not matching `latest` should only be pulled if
      // the image does not exist locally
      rt.execute(
          Executable(
              path = "alpine:mytag",
              serviceId = "sleep",
              arguments = emptyList()
          ),
          DefaultOutputCollector()
      )
      verify { Shell.execute(match { it.windowed(2).any { w ->
        w == listOf("--pull", "missing")
      }}, any(), any(), any()) }
      clearRecordedCalls()

      // an image with a digest should only be pulled if the image does not
      // exist locally
      rt.execute(
          Executable(
              path = "alpine@sha256:abcdefabcdefabcdefabcdefabcdefab",
              serviceId = "sleep",
              arguments = emptyList()
          ),
          DefaultOutputCollector()
      )
      verify { Shell.execute(match { it.windowed(2).any { w ->
        w == listOf("--pull", "missing")
      }}, any(), any(), any()) }
      clearRecordedCalls()

      // an image with a digest should only be pulled if the image does not
      // exist locally, even if the tag is `latest`
      rt.execute(
          Executable(
              path = "alpine:latest@sha256:abcdefabcdefabcdefabcdefabcdefab",
              serviceId = "sleep",
              arguments = emptyList()
          ),
          DefaultOutputCollector()
      )
      verify { Shell.execute(match { it.windowed(2).any { w ->
        w == listOf("--pull", "missing")
      }}, any(), any(), any()) }
      clearRecordedCalls()
    }
  }
}
