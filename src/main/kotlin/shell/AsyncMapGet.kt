package shell

import io.vertx.core.cli.annotations.Argument
import io.vertx.core.cli.annotations.Description
import io.vertx.core.cli.annotations.Name
import io.vertx.core.cli.annotations.Summary
import io.vertx.ext.shell.command.AnnotatedCommand
import io.vertx.ext.shell.command.CommandProcess
import io.vertx.kotlin.core.shareddata.getAsyncMapAwait
import io.vertx.kotlin.core.shareddata.getAwait
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Similar to [io.vertx.ext.shell.command.base.LocalMapGet] but for async maps
 * @author Michel Kraemer
 */
@Name("async-map-get")
@Summary("Get values from an async map")
class AsyncMapGet : AnnotatedCommand() {
  private var map: String? = null
  private var key: String? = null

  @Argument(index = 0, argName = "map")
  @Description("the name of the map to get from")
  fun setMap(map: String) {
    this.map = map
  }

  @Argument(index = 1, argName = "key")
  @Description("the key to get")
  fun setKey(key: String) {
    this.key = key
  }

  override fun process(process: CommandProcess) {
    val vertx = process.vertx()
    GlobalScope.launch(vertx.dispatcher()) {
      try {
        val sharedData = vertx.sharedData()
        val m = sharedData.getAsyncMapAwait<String, Any>(map!!)
        val value = m.getAwait(key!!)
        process.write("$key: $value\n")
      } catch (t: Throwable) {
        process.write(t.message)
      }
      process.end()
    }
  }
}
