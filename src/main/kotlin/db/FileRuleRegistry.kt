package db

import io.vertx.core.Vertx
import model.rules.Rule

/**
 * A metadata registry that reads rules from JSON or YAML files
 * @param paths the paths or globs to the JSON or YAML files
 * @param vertx the Vert.x instance
 * @author Michel Kraemer
 */
class FileRuleRegistry(private val paths: List<String>, private val vertx: Vertx) :
    RuleRegistry, AbstractFileRegistry() {
  private var rules: List<Rule>? = null

  override suspend fun findRules(): List<Rule> {
    if (rules == null) {
      rules = find(paths, vertx)
    }
    return rules!!
  }
}
