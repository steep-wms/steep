package shell

import io.vertx.core.cli.annotations.Argument
import io.vertx.core.cli.annotations.Description
import io.vertx.core.cli.annotations.Name
import io.vertx.core.cli.annotations.Summary
import io.vertx.ext.shell.command.AnnotatedCommand
import io.vertx.ext.shell.command.CommandProcess
import io.vertx.kotlin.core.shareddata.getAsyncMapAwait
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Shows all entries of an async map
 * @author Michel Kraemer
 */
@Name("async-map-entries")
@Summary("Get entries from an async map")
class AsyncMapEntries : AnnotatedCommand() {
  private var map: String? = null

  @Argument(index = 0, argName = "map")
  @Description("the name of the map to get from")
  fun setMap(map: String) {
    this.map = map
  }

  override fun process(process: CommandProcess) {
    val vertx = process.vertx()
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        val sharedData = vertx.sharedData()
        val m = sharedData.getAsyncMapAwait<String, Any>(map!!)
        val entries = awaitResult<Map<String, Any>> { m.entries(it) }
        for ((k, v) in entries) {
          process.write("$k: $v\n")
        }
      } catch (t: Throwable) {
        process.write(t.message)
      }
      process.end()
    }
  }
}
