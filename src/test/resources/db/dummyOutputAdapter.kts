import io.vertx.core.Vertx
import model.processchain.Argument
import model.processchain.ProcessChain

fun dummyOutputAdapter(output: Argument, processChain: ProcessChain,
    vertx: Vertx): List<String> {
  return listOf("${output.variable.value}1", "${output.variable.value}2")
}
