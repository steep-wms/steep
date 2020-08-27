import io.vertx.core.Vertx
import model.processchain.Executable

fun dummyProgressEstimator(executable: Executable, recentLines: List<String>, vertx: Vertx): Double? {
  return recentLines.first().toDoubleOrNull()?.let { it / 100 }
}
