package search

import org.apache.commons.text.StringEscapeUtils
import org.parboiled.Action
import org.parboiled.BaseParser
import org.parboiled.Context
import org.parboiled.MatcherContext
import org.parboiled.Rule
import org.parboiled.matchers.CustomMatcher
import org.parboiled.support.StringVar
import org.parboiled.support.Var
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Parses search queries. Do not use this class directly. Use [QueryCompiler] instead!
 * @author Michel Kraemer
 */
open class QueryParser(private val timeZone: ZoneId) : BaseParser<QueryParser.QueryParserNode>() {
  data class QueryParserNode(
      val term: Term? = null,
      val filter: Pair<Locator, Term>? = null,
      val locator: Locator? = null,
      val type: Type? = null
  )

  /**
   * Start rule
   */
  open fun query(): Rule =
      Sequence(
          OneOrMore(expr()),
          EOI
      )

  open fun expr(): Rule =
      FirstOf(
          filter(),
          inLocator(),
          type(),
          term()
      )

  open fun quotedStringTerm(quoteCharacter: Char): Rule {
    val str = StringVar()
    return Sequence(
        quotedString(quoteCharacter, str),
        object : Action<Term> {
          override fun run(context: Context<Term>): Boolean {
            push(QueryParserNode(term = StringTerm(StringEscapeUtils.unescapeJava(str.get()))))
            return true
          }
        },
        ws()
    )
  }

  open fun dateTimeTerm(): Rule {
    val op = Var(Operator.EQ)
    val date = Var<LocalDate>()
    val time = Var<Pair<LocalTime, Boolean>>()
    return Sequence(
        Optional(operator(op)),
        date(date),
        Optional(
            'T',
            time(time)
        ),
        ws(),
        object : Action<QueryParserNode> {
          override fun run(ctx: Context<QueryParserNode>): Boolean {
            return try {
              val t = if (time.isSet) {
                DateTimeTerm(
                    date.get().atTime(time.get().first),
                    timeZone = timeZone,
                    withSecondPrecision = time.get().second,
                    operator = op.get()
                )
              } else {
                DateTerm(
                    date.get(),
                    timeZone = timeZone,
                    operator = op.get()
                )
              }
              ctx.valueStack.push(QueryParserNode(term = t))
              true
            } catch (_: DateTimeException) {
              false
            }
          }
        }
    )
  }

  open fun dateTimeRangeTerm(): Rule {
    val dateFrom = Var<LocalDate>()
    val timeFrom = Var<Pair<LocalTime, Boolean>>()
    val dateTo = Var<LocalDate>()
    val timeTo = Var<Pair<LocalTime, Boolean>>()
    return Sequence(
        date(dateFrom),
        Optional(
            'T',
            time(timeFrom)
        ),
        "..",
        date(dateTo),
        Optional(
            'T',
            time(timeTo)
        ),
        ws(),
        object : Action<QueryParserNode> {
          override fun run(ctx: Context<QueryParserNode>): Boolean {
            return try {
              ctx.valueStack.push(QueryParserNode(term = DateTimeRangeTerm(
                  dateFrom.get(),
                  timeFrom.get()?.first,
                  timeFrom.get()?.second ?: false,
                  dateTo.get(),
                  timeTo.get()?.first,
                  timeTo.get()?.second ?: false,
                  timeZone
              )))
              true
            } catch (_: DateTimeException) {
              false
            }
          }
        }
    )
  }

  open fun operator(op: Var<Operator>): Rule {
    return FirstOf(
        Sequence("<=", op.set(Operator.LTE)),
        Sequence(">=", op.set(Operator.GTE)),
        Sequence('<', op.set(Operator.LT)),
        Sequence('>', op.set(Operator.GT))
    )
  }

  open fun date(date: Var<LocalDate>): Rule {
    val year = Var<Int>()
    val month = Var<Int>()
    val day = Var<Int>()
    return Sequence(
        NTimes(4, digit()),
        year.set(match().toInt()),
        '-',
        NTimes(2, digit()),
        month.set(match().toInt()),
        '-',
        NTimes(2, digit()),
        day.set(match().toInt()),
        object : Action<QueryParserNode> {
          override fun run(ctx: Context<QueryParserNode>): Boolean {
            return try {
              date.set(LocalDate.of(year.get(), month.get(), day.get()))
              true
            } catch (_: DateTimeException) {
              false
            }
          }
        }
    )
  }

  open fun time(time: Var<Pair<LocalTime, Boolean>>): Rule {
    val hour = Var<Int>()
    val minute = Var<Int>()
    val second = Var<Int>()
    return Sequence(
        NTimes(2, digit()),
        hour.set(match().toInt()),
        ':',
        NTimes(2, digit()),
        minute.set(match().toInt()),
        Optional(
            ':',
            NTimes(2, digit()),
            second.set(match().toInt())
        ),
        object : Action<QueryParserNode> {
          override fun run(ctx: Context<QueryParserNode>): Boolean {
            return try {
              time.set(LocalTime.of(hour.get(), minute.get(), second.get() ?: 0) to second.isSet)
              true
            } catch (_: DateTimeException) {
              false
            }
          }
        }
    )
  }

  open fun filter(): Rule {
    val loc = Var<Locator>()
    return Sequence(
        locator(loc),
        ':',
        term(),
        push(QueryParserNode(filter = loc.get() to pop().term!!))
    )
  }

  open fun inLocator(): Rule {
    val loc = Var<Locator>()
    return Sequence(
        IgnoreCase("in:"),
        FirstOf(
            Sequence('"', locator(loc), '"'),
            Sequence('\'', locator(loc), '\''),
            locator(loc)
        ),
        ws(),
        push(QueryParserNode(locator = loc.get()))
    )
  }

  open fun type(): Rule {
    return Sequence(
        IgnoreCase("is:"),
        FirstOf(
            Sequence(
                FirstOf(
                    IgnoreCase("\"workflow\""),
                    IgnoreCase("'workflow'"),
                    IgnoreCase("workflow")
                ),
                push(QueryParserNode(type = Type.WORKFLOW))
            ),
            Sequence(
                FirstOf(
                    IgnoreCase("\"processchain\""),
                    IgnoreCase("'processchain'"),
                    IgnoreCase("processchain")
                ),
                push(QueryParserNode(type = Type.PROCESS_CHAIN))
            )
        ),
        ws()
    )
  }

  open fun term(): Rule =
      FirstOf(
          quotedStringTerm('"'),
          quotedStringTerm('\''),
          dateTimeTerm(),
          dateTimeRangeTerm(),
          stringTerm()
      )

  open fun locator(loc: Var<Locator>): Rule = FirstOf(
      Sequence(IgnoreCase("id"), loc.set(Locator.ID)),
      Sequence(IgnoreCase("name"), loc.set(Locator.NAME)),
      Sequence(FirstOf(IgnoreCase("errormessage"), IgnoreCase("error")),
          loc.set(Locator.ERROR_MESSAGE)),
      Sequence(FirstOf(IgnoreCase("rcs"), IgnoreCase("caps"), IgnoreCase("reqcaps"),
          IgnoreCase("capabilities"), IgnoreCase("requiredcapabilities"),
          IgnoreCase("rc"), IgnoreCase("cap"), IgnoreCase("reqcap"),
          IgnoreCase("capability"), IgnoreCase("requiredcapability")),
          loc.set(Locator.REQUIRED_CAPABILITIES)),
      Sequence(IgnoreCase("source"), loc.set(Locator.SOURCE)),
      Sequence(IgnoreCase("status"), loc.set(Locator.STATUS)),
      Sequence(FirstOf(IgnoreCase("starttime"), IgnoreCase("start")),
          loc.set(Locator.START_TIME)),
      Sequence(FirstOf(IgnoreCase("endtime"), IgnoreCase("end")),
          loc.set(Locator.END_TIME))
  )

  open fun stringTerm(): Rule {
    val str = StringVar()
    return Sequence(
        string(str),
        push(QueryParserNode(term = StringTerm(str.get()))),
        ws()
    )
  }

  open fun string(str: StringVar): Rule =
      Sequence(OneOrMore(TestNot(WhitespaceMatcher()), ANY), str.set(match()))

  open fun quotedString(quoteCharacter: Char, str: StringVar): Rule =
      Sequence(
          Sequence(
            quoteCharacter,
            ZeroOrMore(
                FirstOf(
                    escape(),
                    Sequence(TestNot(AnyOf("$quoteCharacter\\")), ANY)
                )
            ),
            quoteCharacter,
          ),
          object : Action<QueryParserNode> {
            override fun run(context: Context<QueryParserNode>): Boolean {
              str.set(match().substring(1, matchLength() - 1))
              return true
            }
          }
      )

  open fun escape(): Rule {
    return Sequence(
        '\\',
        AnyOf("\"\'\\")
    )
  }

  open fun digit(): Rule =
      CharRange('0', '9')

  open fun ws(): Rule =
      FirstOf(OneOrMore(WhitespaceMatcher()), EOI)

  class WhitespaceMatcher : CustomMatcher("WS") {
    override fun <V : Any> match(context: MatcherContext<V>): Boolean {
      if (!Character.isWhitespace(context.currentChar)) {
        return false
      }
      context.advanceIndex(1)
      context.createNode()
      return true
    }

    override fun isSingleCharMatcher() = false
    override fun canMatchEmpty() = false
    override fun isStarterChar(c: Char) = Character.isWhitespace(c)
    override fun getStarterChar(): Char = ' '
  }
}
