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
  private var macros: List<Macro>? = null

  override suspend fun findMacros(): List<Macro> {
    if (macros == null) {
      val m: List<Macro> = find(paths, vertx)

      // check macros for duplicate IDs
      val macroIds = mutableSetOf<String>()
      for (macro in m) {
        if (macroIds.contains(macro.id)) {
          throw IllegalArgumentException("Found duplicate macro ID: `${macro.id}'")
        }
        macroIds.add(macro.id)
      }

      macros = m
    }
    return macros!!
  }
}
