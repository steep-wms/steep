package helper

import coVerify
import io.vertx.core.Vertx
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [glob]
 * @author Michel Kraemer
 */
@ExtendWith(VertxExtension::class)
class GlobTest {
  /**
   * Test if we can search an empty directory with an absolute path
   */
  @Test
  fun emptyDirectoryAbsolute(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val r = glob(tempDir.toFile().absolutePath + "/**")
        assertThat(r).isEmpty()
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can find a single file with an absolute path
   */
  @Test
  fun singleFileAbsolute(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val f = File(tempDir.toFile(), "test.txt")
    f.writeText("Hello world!")
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val r = glob(f.absolutePath)
        assertThat(r).hasSize(1)
        assertThat(r[tempDir.toFile().absolutePath]).containsExactly("test.txt")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can find a single file with a relative path
   */
  @Test
  fun singleFileRelative(vertx: Vertx, ctx: VertxTestContext) {
    val file1 = "src/test/resources/helper/glob/dummy.txt"
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val r = glob(file1)
        assertThat(r).hasSize(1)
        assertThat(r["src/test/resources/helper/glob"]).containsExactly("dummy.txt")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test if we can find a two files in a relative directory
   */
  @Test
  fun twoFilesRelative(vertx: Vertx, ctx: VertxTestContext) {
    val path = "src/test/resources/helper/glob"
    val pattern = "$path/*"
    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val r = glob(pattern)
        assertThat(r).hasSize(1)
        assertThat(r["src/test/resources/helper/glob"]).containsExactlyInAnyOrder(
            "dummy.txt", "dummy2.txt")
      }
      ctx.completeNow()
    }
  }

  /**
   * Test multple patterns
   */
  @Test
  fun multiple(vertx: Vertx, ctx: VertxTestContext, @TempDir tempDir: Path) {
    val f = File(tempDir.toFile(), "test.txt")
    f.writeText("Hello world!")

    val tempSubdir = File(tempDir.toFile(), "subdir")
    tempSubdir.mkdirs()
    val f2 = File(tempSubdir, "test2.txt")
    f2.writeText("Another file")

    val path = "src/test/resources/helper/glob"
    val pattern = "$path/*"

    GlobalScope.launch(vertx.dispatcher()) {
      ctx.coVerify {
        val r = glob("${tempDir.toFile().absolutePath}/**/*", pattern)
        assertThat(r).hasSize(2)
        assertThat(r[tempDir.toFile().absolutePath]).containsExactlyInAnyOrder(
            "test.txt", "subdir/test2.txt")
        assertThat(r["src/test/resources/helper/glob"]).containsExactlyInAnyOrder(
            "dummy.txt", "dummy2.txt")
      }
      ctx.completeNow()
    }
  }
}
