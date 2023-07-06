package db

import io.vertx.core.Vertx
import model.macro.Macro

/**
 * A metadata registry that reads macros from JSON or YAML files
 * @param paths the paths or globs to the JSON or YAML files
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class FileMacroRegistry(private val paths: List<String>, private val vertx: Vertx) :
    MacroRegistry, AbstractFileRegistry() {
  private var macros: HashMap<String, Macro>? = null

  override suspend fun findMacros(): HashMap<String, Macro> {
    if (macros == null) {
      val m: List<Macro> = find(paths, vertx)

      val r = LinkedHashMap<String, Macro>()
      for (macro in m) {
        // check macros for duplicate IDs
        if (r.containsKey(macro.id)) {
          throw IllegalArgumentException("Found duplicate macro ID: `${macro.id}'")
        }
        r[macro.id] = macro
      }

      macros = r
    }
    return macros!!
  }
}
