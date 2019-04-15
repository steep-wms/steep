package db

import model.rules.Rule

/**
 * A registry containing rules
 * @author Michel Kraemer
 */
interface RuleRegistry {
  /**
   * Return a list of all rules
   */
  suspend fun findRules(): List<Rule>
}
