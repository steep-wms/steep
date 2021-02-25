import helper.OutputCollector
import io.vertx.core.Vertx
import model.processchain.Executable

fun dummyRuntime(executable: Executable, outputCollector: OutputCollector, vertx: Vertx) {
  outputCollector.collect("DUMMY")
}
