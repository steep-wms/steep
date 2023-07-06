package db

import model.macro.Macro

/**
 * A registry containing all macros
 * @author Michel Kraemer
 */
interface MacroRegistry {
  /**
   * Get a map of all macro IDs and macros
   */
  suspend fun findMacros(): HashMap<String, Macro>
}
