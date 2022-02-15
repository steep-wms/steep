import io.vertx.core.Vertx
import model.processchain.ProcessChain

fun deprecatedProcessChainAdapter(processChains: List<ProcessChain>, vertx: Vertx):
      List<ProcessChain> {
  return processChains + listOf(ProcessChain())
}
