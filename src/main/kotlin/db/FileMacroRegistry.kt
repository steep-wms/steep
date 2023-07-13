package db

import helper.WorkflowValidator
import io.vertx.core.Vertx
import model.macro.Macro
import org.apache.commons.text.WordUtils
import org.slf4j.LoggerFactory

/**
 * A metadata registry that reads macros from JSON or YAML files
 * @param paths the paths or globs to the JSON or YAML files
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class FileMacroRegistry(private val paths: List<String>, private val vertx: Vertx) :
    MacroRegistry, AbstractFileRegistry() {
  private val log = LoggerFactory.getLogger(FileMacroRegistry::class.java)
  private var macros: HashMap<String, Macro>? = null

  override suspend fun findMacros(): HashMap<String, Macro> {
    if (macros == null) {
      val m: List<Macro> = find(paths, vertx)
      var hasErrors = false

      val r = LinkedHashMap<String, Macro>()
      for (macro in m) {
        // check macros for duplicate IDs
        if (r.containsKey(macro.id)) {
          log.error("Found duplicate macro ID: `${macro.id}'")
          hasErrors = true
        }
        r[macro.id] = macro
      }

      for (macro in r.values) {
        val validationResults = WorkflowValidator.validate(macro, r)
        if (validationResults.isNotEmpty()) {
          log.error("Invalid macro `${macro.id}':\n\n" +
              validationResults.joinToString("\n\n") {
                "- ${WordUtils.wrap(it.message, 80, "\n  ", true)}\n\n  " +
                    "${WordUtils.wrap(it.details, 80, "\n  ", true)}\n\n  " +
                    it.path.joinToString("->")
              }
          )
          hasErrors = true
        }
      }

      if (hasErrors) {
        throw IllegalArgumentException("Invalid macro configuration. See " +
            "log for details.")
      }

      macros = r
    }
    return macros!!
  }
}
