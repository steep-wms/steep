package db

import model.macro.Macro

/**
 * A registry containing all macros
 * @author Michel Kraemer
 */
interface MacroRegistry {
  /**
   * Get a list of all macros
   */
  suspend fun findMacros(): List<Macro>
}
