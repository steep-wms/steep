import io.vertx.core.Vertx
import io.vertx.kotlin.core.file.existsAwait
import model.processchain.Argument
import model.processchain.ProcessChain

suspend fun fileOrEmptyList(output: Argument, processChain: ProcessChain,
    vertx: Vertx): List<String> {
  return if (!vertx.fileSystem().existsAwait(output.variable.value)) {
    emptyList()
  } else {
    listOf(output.variable.value)
  }
}
