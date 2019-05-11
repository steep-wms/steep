import io.vertx.core.Vertx
import model.processchain.Executable

fun dummyRuntime(executable: Executable, outputLinesToCollect: Int, vertx: Vertx): String {
  return "DUMMY"
}
