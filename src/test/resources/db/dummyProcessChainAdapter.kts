import io.vertx.core.Vertx
import model.processchain.ProcessChain
import model.workflow.Workflow

fun dummyProcessChainAdapter(processChains: List<ProcessChain>, workflow: Workflow, vertx: Vertx):
      List<ProcessChain> {
  return processChains + listOf(ProcessChain(id = workflow.name!!))
}
