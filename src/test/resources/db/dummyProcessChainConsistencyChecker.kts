import io.vertx.core.Vertx
import model.processchain.Executable
import model.workflow.ExecuteAction
import model.workflow.Workflow

fun dummyProcessChainConsistencyChecker(processChain: List<Executable>,
    action: ExecuteAction, workflow: Workflow, vertx: Vertx): Boolean {
  return processChain.none { it.id == action.id }
}
