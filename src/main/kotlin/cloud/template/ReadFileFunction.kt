package cloud.template

import io.pebbletemplates.pebble.extension.Function
import io.pebbletemplates.pebble.template.EvaluationContext
import io.pebbletemplates.pebble.template.PebbleTemplate
import java.io.File

/**
 * A custom function for the template engine that the [cloud.CloudManager] uses
 * for provisioning scripts. This function reads the contents of a file. Use it
 * as follows:
 *
 *     {{ readFile(filename) }}
 *
 * For example:
 *
 *     {{ readFile("conf/steep.yaml") }}
 *
 * Paths can be absolute or relative to the current working directory (typically
 * Steep's application directory)
 *
 * @author Michel Kraemer
 */
class ReadFileFunction : Function {
  override fun getArgumentNames(): MutableList<String> {
    return mutableListOf("path")
  }

  override fun execute(args: MutableMap<String, Any>, self: PebbleTemplate,
      context: EvaluationContext, lineNumber: Int): Any {
    return File(args["path"].toString()).readText()
  }
}
