import io.vertx.core.Vertx
import model.processchain.ProcessChain

fun dummyProcessChainAdapter(processChains: List<ProcessChain>, vertx: Vertx):
      List<ProcessChain> {
  return processChains + listOf(ProcessChain())
}
