import com.fasterxml.jackson.module.kotlin.readValue
import helper.JsonUtils
import model.processchain.ProcessChain
import model.rules.Rule
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.slf4j.LoggerFactory

/**
 * Applies to rules to process chains and other objects. Instances of this
 * class should be closed if not needed anymore to free resources (see [close]).
 * @author Michel Kraemer
 */
class RuleSystem(rules: List<Rule>): AutoCloseable {
  companion object {
    private val log = LoggerFactory.getLogger(RuleSystem::class.java)
  }

  /**
   * A rule whose source has been evaluated in a JavaScript context
   */
  private data class CompiledRule(
      val name: String,
      val target: Rule.Target,
      val condition: Value,
      val action: Value
  )

  /**
   * A JavaScript context
   */
  private val ctx: Context = Context.create("js")

  /**
   * A list of compiled rules
   */
  private val compiledRules = rules.map { rule ->
    try {
      CompiledRule(rule.name, rule.target, ctx.eval("js", "(${rule.condition})"),
          ctx.eval("js", "(${rule.action})"))
    } catch (e: PolyglotException) {
      log.error("Could not evaluate rule `$rule.name'", e)
      throw e
    }
  }

  /**
   * The `JSON.stringify()` function
   */
  private val stringify = ctx.getBindings("js").getMember("JSON").getMember("stringify")

  override fun close() {
    ctx.close(true)
  }

  /**
   * Apply a [rule] to a [value] (JavaScript object). Return `true` if the
   * rule's action was called, `false` if the rule's condition was not satisfied.
   */
  private fun applyRule(rule: CompiledRule, value: Value): Boolean {
    val conditionResult = rule.condition.getMember("call").execute(value, value)
    if (!conditionResult.isBoolean) {
      throw IllegalStateException("Condition must return a boolean value")
    }
    if (conditionResult.asBoolean()) {
      rule.action.getMember("call").executeVoid(value, value)
      return true
    }
    return false
  }

  /**
   * Apply all rules to the given list of [processChains] and return the
   * modified process chains.
   */
  fun apply(processChains: List<ProcessChain>): List<ProcessChain> {
    // convert process chains to JavaScript objects
    val processChainValues = mutableListOf<Value>()
    for (processChain in processChains) {
      val obj = JsonUtils.mapper.writeValueAsString(processChain)
      val source = Source.newBuilder("js", "($obj)", "parseProcessChainJSON")
          .cached(false) // we'll most likely never convert the same object again
          .build()
      processChainValues.add(ctx.eval(source))
    }

    // apply all rules
    var changed = false
    for (rule in compiledRules) {
      for (processChainValue in processChainValues) {
        val c = when (rule.target) {
          Rule.Target.PROCESSCHAIN -> applyRule(rule, processChainValue)

          Rule.Target.EXECUTABLE -> {
            var c2 = false
            val executablesValue = processChainValue.getMember("executables")
            for (i in 0 until executablesValue.arraySize) {
              if (applyRule(rule, executablesValue.getArrayElement(i))) {
                c2 = true
              }
            }
            c2
          }
        }

        changed = c || changed
      }
    }

    // convert JS objects back to process chains if necessary
    return if (changed) {
      processChainValues.map {
        val str = stringify.execute(it)
        JsonUtils.mapper.readValue<ProcessChain>(str.asString())
      }
    } else {
      processChains
    }
  }
}
