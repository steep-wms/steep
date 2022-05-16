package db

import io.vertx.core.Vertx
import model.setup.Setup

/**
 * A registry containing all configured virtual machine setups
 * @author Michel Kraemer
 */
class SetupRegistry(private val setupsFile: String, private val vertx: Vertx) : AbstractFileRegistry() {
  private var setups: List<Setup>? = null

  /**
   * Return all configured virtual machine setups
   */
  suspend fun findSetups(): List<Setup> {
    if (setups == null) {
      val s: List<Setup> = find(listOf(setupsFile), vertx)

      // check setups for duplicate IDs
      val setupIds = mutableSetOf<String>()
      for (setup in s) {
        if (setupIds.contains(setup.id)) {
          throw IllegalArgumentException("Found duplicate setup ID: `${setup.id}'")
        }
        setupIds.add(setup.id)
      }

      setups = s
    }
    return setups!!
  }
}
