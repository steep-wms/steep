package runtime

import helper.DefaultOutputCollector
import helper.UniqueID
import io.vertx.core.json.JsonObject
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
   * Create a default configuration object to pass to the container runtime
   */
  fun createDefaultConfig(tempDir: Path): JsonObject

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

    val runtime = createRuntime(createDefaultConfig(tempDir))
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

    val runtime = createRuntime(createDefaultConfig(tempDir))
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

    val runtime = createRuntime(createDefaultConfig(tempDir))
    val collector = DefaultOutputCollector()
    assertThatThrownBy { runtime.execute(exec, collector) }
        .isInstanceOf(IOException::class.java)
  }
}
