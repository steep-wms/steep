package db

import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import helper.YamlUtils
import model.rules.Rule
import java.io.File

/**
 * A metadata registry that reads rules from a JSON or YAML file
 * @param path the path to the JSON or YAML file
 * @author Michel Kraemer
 */
class FileRuleRegistry(path: String) : RuleRegistry {
  private val rules: List<Rule> = if (path.toLowerCase().endsWith(".json")) {
    JsonUtils.mapper.readValue(File(path))
  } else {
    YamlUtils.mapper.readValue(File(path))
  }

  override suspend fun findRules(): List<Rule> = rules
}
