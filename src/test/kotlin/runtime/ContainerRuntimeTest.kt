package runtime

import helper.DefaultOutputCollector
import helper.UniqueID
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.json.jsonObjectOf
import model.metadata.Service
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Common code for all tests related to container runtimes
 * @author Michel Kraemer
 */
interface ContainerRuntimeTest {
  companion object {
    @JvmStatic
    val EXPECTED = "Elvis"
  }

  /**
   * Create a configuration object to pass to the container runtime
   */
  fun createConfig(tempDir: Path, additional: JsonObject = jsonObjectOf()): JsonObject

  /**
   * Create the container runtime
   */
  fun createRuntime(config: JsonObject): Runtime

  /**
   * Test that a simple container can be executed and that its output can be
   * collected
   */
  @Test
  fun executeEcho(@TempDir tempDir: Path) {
    val exec = Executable(path = "alpine", serviceId = "echo", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), EXPECTED),
            type = Argument.Type.INPUT)
    ))

    val runtime = createRuntime(createConfig(tempDir))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a simple container can be executed and that its output (multiple
   * lines) can be collected
   */
  @Test
  fun executeEchoMultiline(@TempDir tempDir: Path) {
    val exec = Executable(path = "alpine", serviceId = "myservice", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sh"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "-c"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo Hello && sleep 0.1 && echo World"),
            type = Argument.Type.INPUT)
    ))

    val runtime = createRuntime(createConfig(tempDir))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo("Hello\nWorld")
  }

  /**
   * Test that a failure is correctly detected
   */
  @Test
  fun failure(@TempDir tempDir: Path) {
    val exec = Executable(path = "alpine", serviceId = "false", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "false"),
            type = Argument.Type.INPUT),
    ))

    val runtime = createRuntime(createConfig(tempDir))
    val collector = DefaultOutputCollector()
    assertThatThrownBy { runtime.execute(exec, collector) }
        .isInstanceOf(IOException::class.java)
  }

  fun doExecuteVolumeConf(@TempDir tempDir: Path, @TempDir tempDir2: Path,
      additionalConfig: JsonObject) {
    val f = File(tempDir2.toFile(), "test.txt")
    f.writeText(EXPECTED)

    val exec = Executable(path = "alpine", serviceId = "cat", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "cat"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "/tmp/test.txt"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = createRuntime(createConfig(tempDir, additionalConfig))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a container can be executed with a mounted volume specified in
   * the configuration object
   *
   * Should call [doExecuteVolumeConf]
   */
  @Test
  fun executeVolumeConf(@TempDir tempDir: Path, @TempDir tempDir2: Path)

  fun doExecuteEnvConf(@TempDir tempDir: Path, additionalConfig: JsonObject) {
    val exec = Executable(path = "alpine", serviceId = "sh", arguments = listOf(
        Argument(variable = ArgumentVariable(UniqueID.next(), "sh"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "-c"),
            type = Argument.Type.INPUT),
        Argument(variable = ArgumentVariable(UniqueID.next(), "echo \$MYVAR"),
            type = Argument.Type.INPUT)
    ), runtime = Service.RUNTIME_DOCKER)

    val runtime = createRuntime(createConfig(tempDir, additionalConfig))
    val collector = DefaultOutputCollector()
    runtime.execute(exec, collector)
    assertThat(collector.output()).isEqualTo(EXPECTED)
  }

  /**
   * Test that a container can be executed with an environment variable
   * specified in the configuration object
   *
   * Should call [doExecuteEnvConf]
   */
  @Test
  fun executeEnvConf(@TempDir tempDir: Path)
}
