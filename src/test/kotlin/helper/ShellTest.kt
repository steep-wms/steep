package helper

import org.apache.commons.io.FileUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for [Shell]
 * @author Michel Kraemer
 */
class ShellTest {
  /**
   * Tests that output is captured correctly
   */
  @Test
  fun echoOk() {
    assertThat(Shell.execute(listOf("echo", "ok"))).isEqualTo("ok")
  }

  /**
   * Test that the [Shell.execute] method acutally executes something
   */
  @Test
  fun cp() {
    // TODO replace by TemporaryDirectory once JUnit 5.4 is released
    val tempDir1 = Files.createTempDirectory(null).toRealPath()
    val tempDir2 = Files.createTempDirectory(null).toRealPath()

    // create test file
    val inputFile = File(tempDir1.toFile(), "test.txt")
    inputFile.writeText("Hello world")
    inputFile.deleteOnExit()

    try {
      Shell.execute(listOf("cp", inputFile.toString(), tempDir2.toString()))
      val outputFile = File(tempDir2.toFile(), "test.txt")
      assertThat(outputFile).exists().hasSameContentAs(inputFile)
    } finally {
      // TODO we might be able to remove this once we upgrade to JUnit 5.4
      FileUtils.deleteDirectory(tempDir1.toFile())
      FileUtils.deleteDirectory(tempDir2.toFile())
    }
  }

  /**
   * Check if an exception is thrown if a command exits with code != 0
   */
  @Test
  fun error() {
    assertThatThrownBy { Shell.execute(listOf("false")) }
        .isInstanceOf(Shell.ExecutionException::class.java)
        .hasFieldOrPropertyWithValue("exitCode", 1)
  }
}
