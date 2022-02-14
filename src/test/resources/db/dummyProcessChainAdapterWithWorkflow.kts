import io.vertx.core.Vertx
import model.processchain.ProcessChain
import model.workflow.Workflow

fun dummyProcessChainAdapterWithWorkflow(processChains: List<ProcessChain>, workflow: Workflow, vertx: Vertx):
      List<ProcessChain> {
  return processChains
}
