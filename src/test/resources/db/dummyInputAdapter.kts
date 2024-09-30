import io.vertx.core.Vertx
import model.processchain.Argument
import model.processchain.ProcessChain

fun dummyInputAdapter(input: Argument, processChain: ProcessChain,
    vertx: Vertx): List<Argument> {
  return listOf(input.copy(label = "-a"), input.copy(label = "-b"))
}
