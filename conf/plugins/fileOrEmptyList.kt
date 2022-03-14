@file:Suppress("UNUSED_PARAMETER")

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.await
import model.processchain.Argument
import model.processchain.ProcessChain

suspend fun fileOrEmptyList(output: Argument, processChain: ProcessChain,
    vertx: Vertx): List<String> {
  return if (!vertx.fileSystem().exists(output.variable.value).await()) {
    emptyList()
  } else {
    listOf(output.variable.value)
  }
}
