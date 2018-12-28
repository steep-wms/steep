package agent

import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.processchain.Argument
import model.processchain.Executable
import model.processchain.ProcessChain
import org.apache.commons.io.FileUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import java.nio.file.Files

/**
 * Tests for all [Agent] implementations
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
abstract class AgentTest {
  abstract val agent: Agent

  /**
   * Executes a process chain that copies a file from a temporary directory to
   * another one
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  fun execute(vertx: Vertx, ctx: VertxTestContext) {
    // TODO replace by TemporaryDirectory once JUnit 5.4 is released
    val tempDir1 = Files.createTempDirectory(null).toRealPath()
    val tempDir2 = Files.createTempDirectory(null).toRealPath()

    // create test file
    val inputFile = File(tempDir1.toFile(), "test.txt")
    inputFile.writeText("Hello world")
    inputFile.deleteOnExit()

    // create process chain that copies the test file from tempDir1 to tempDir2
    val outputArg = Argument(value = tempDir2.toString(),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "cp", arguments = listOf(
            Argument(value = inputFile.toString(), type = Argument.Type.INPUT),
            outputArg
        ))
    ))

    GlobalScope.launch(vertx.dispatcher()) {
      try {
        // execute process chain
        val results = agent.execute(processChain)

        // check results
        ctx.verify {
          val outputFile = File(tempDir2.toFile(), inputFile.name)
          assertThat(outputFile)
              .exists()
              .hasSameContentAs(inputFile)
          assertThat(results)
              .hasSize(1)
              .contains(entry(outputArg.id, listOf(outputFile.path)))
        }

        ctx.completeNow()
      } finally {
        // TODO we might be able to remove this once we upgrade to JUnit 5.4
        FileUtils.deleteDirectory(tempDir1.toFile())
        FileUtils.deleteDirectory(tempDir2.toFile())
      }
    }
  }
}
