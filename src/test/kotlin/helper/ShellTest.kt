package helper

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.concurrent.thread

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
   * Test that the [Shell.execute] method actually executes something
   */
  @Test
  fun cp(@TempDir tempDir: Path) {
    val tempDir1 = File(tempDir.toFile(), "src")
    val tempDir2 = File(tempDir.toFile(), "dst")
    tempDir1.mkdirs()
    tempDir2.mkdirs()

    // create test file
    val inputFile = File(tempDir1, "test.txt")
    inputFile.writeText("Hello world")

    Shell.execute(listOf("cp", inputFile.toString(), tempDir2.toString()))
    val outputFile = File(tempDir2, "test.txt")
    assertThat(outputFile).exists().hasSameContentAs(inputFile)
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

  /**
   * Test if a process can be destroyed by interrupting the current thread
   */
  @Test
  fun interrupt() {
    val t = Thread.currentThread()

    thread {
      Thread.sleep(200)
      t.interrupt()
    }

    assertThatThrownBy { Shell.execute(listOf("sleep", "10")) }
        .isInstanceOf(InterruptedException::class.java)
  }
}
