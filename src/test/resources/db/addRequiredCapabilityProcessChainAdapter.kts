import io.vertx.core.Vertx
import model.processchain.ProcessChain

fun addRequiredCapabilityProcessChainAdapter(processChains: List<ProcessChain>, vertx: Vertx):
      List<ProcessChain> {
  return processChains.map { it.copy(requiredCapabilities =
      it.requiredCapabilities + setOf("NewRequiredCapability")) }
}
