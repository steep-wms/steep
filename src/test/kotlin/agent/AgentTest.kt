package agent

import coVerify
import helper.UniqueID
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import model.processchain.Argument
import model.processchain.ArgumentVariable
import model.processchain.Executable
import model.processchain.ProcessChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for all [Agent] implementations
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
abstract class AgentTest {
  abstract fun createAgent(vertx: Vertx): Agent

  /**
   * Executes a process chain that copies a file from a temporary directory to
   * another one
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  open fun execute(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val tempDir1 = File(tempDir.toRealPath().toFile(), "src")
    val tempDir2 = File(tempDir.toRealPath().toFile(), "dst")
    tempDir1.mkdirs()

    // create test file
    val inputFile = File(tempDir1, "test.txt")
    inputFile.writeText("Hello world")

    // create process chain that copies the test file from tempDir1 to tempDir2
    val outputArg = Argument(variable = ArgumentVariable(UniqueID.next(), tempDir2.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "cp", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), inputFile.toString()),
                type = Argument.Type.INPUT),
            outputArg
        ))
    ))

    val agent = createAgent(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        // execute process chain
        val results = agent.execute(processChain)

        // check results
        val outputFile = File(tempDir2, inputFile.name)
        assertThat(outputFile)
            .exists()
            .hasSameContentAs(inputFile)
        assertThat(results)
            .hasSize(1)
            .contains(entry(outputArg.variable.id, listOf(outputFile.path)))
      }

      ctx.completeNow()
    }
  }

  /**
   * Executes a process chain that creates a directory with a new file. Tests
   * if outputs can be read recursively.
   * @param vertx the Vert.x instance
   * @param ctx the test context
   */
  @Test
  open fun recursive(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val tempDir1 = File(tempDir.toRealPath().toFile(), "src")
    val tempDir2 = File(tempDir.toRealPath().toFile(), "dst")
    val newDir = File(tempDir2, "newDir")
    val newFile = File(newDir, "newFile.txt")
    tempDir1.mkdirs()

    // create test file
    val inputFile = File(tempDir1, "test.txt")
    inputFile.writeText("Hello world")

    // create process chain that creates a new directory and then copies the
    // test file from tempDir1 to tempDir2/newDir
    val outputArg = Argument(variable = ArgumentVariable(UniqueID.next(), tempDir2.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val outputNewDirArg = Argument(variable = ArgumentVariable(UniqueID.next(), newDir.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_DIRECTORY)
    val outputNewFileArg = Argument(variable = ArgumentVariable(UniqueID.next(), newFile.toString()),
        type = Argument.Type.OUTPUT,
        dataType = Argument.DATA_TYPE_STRING)
    val processChain = ProcessChain(executables = listOf(
        Executable(path = "mkdir", arguments = listOf(
            Argument(label = "-p", variable = ArgumentVariable(UniqueID.next(), "true"),
                type = Argument.Type.INPUT, dataType = Argument.DATA_TYPE_BOOLEAN),
            outputNewDirArg
        )),
        Executable(path = "touch", arguments = listOf(outputNewFileArg)),
        Executable(path = "cp", arguments = listOf(
            Argument(variable = ArgumentVariable(UniqueID.next(), inputFile.toString()),
                type = Argument.Type.INPUT),
            outputArg
        ))
    ))

    val agent = createAgent(vertx)

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        // execute process chain
        val results = agent.execute(processChain)

        // check results
        val outputFile = File(tempDir2, inputFile.name)
        assertThat(outputFile)
            .exists()
            .hasSameContentAs(inputFile)
        assertThat(newFile)
            .exists()
            .hasContent("")
        assertThat(results)
            .hasSize(3)
            .contains(entry(outputArg.variable.id, listOf(outputFile.path, newFile.path)))
            .contains(entry(outputNewDirArg.variable.id, listOf(newFile.path)))
            .contains(entry(outputNewFileArg.variable.id, listOf(newFile.path)))
      }

      ctx.completeNow()
    }
  }
}
