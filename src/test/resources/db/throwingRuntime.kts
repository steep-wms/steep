import helper.OutputCollector
import io.vertx.core.Vertx
import model.processchain.Executable

fun throwingRuntime(executable: Executable, outputCollector: OutputCollector, vertx: Vertx) {
  throw IllegalStateException("This runtime throws")
}
