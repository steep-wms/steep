import io.vertx.core.Vertx
import model.processchain.ProcessChain
import model.setup.Setup
import model.workflow.Workflow

fun dummySetupAdapter(setup: Setup, requiredCapabilities: Collection<String>): Setup {
  if (requiredCapabilities.isNotEmpty()) {
    return setup.copy(flavor = requiredCapabilities.first())
  }
  return setup
}
