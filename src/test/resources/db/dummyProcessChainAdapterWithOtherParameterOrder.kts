import model.processchain.ProcessChain
import model.workflow.Workflow

fun dummyProcessChainAdapterWithOtherParameterOrder(
    workflow: Workflow, processChains: List<ProcessChain>): List<ProcessChain> {
  return processChains + listOf(ProcessChain(id = workflow.name!!))
}
