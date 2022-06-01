package search

import org.apache.commons.text.StringEscapeUtils
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Compiles search queries to [Query] objects
 *
 * A search query consists of one or more of the following elements:
 *
 * **Term**
 *
 * A string that should appear somewhere in the document to find
 *
 * **Date**
 *
 * A string in the form `yyyy-MM-dd` (e.g. `2022-05-20`)
 *
 * **Date time**
 *
 * A string in the form `yyyy-MM-dd'T'HH:mm[:ss]` (e.g. `2022-05-20T16:36` or
 * `2022-05-20T16:36:12`
 *
 * **Time range**
 *
 * A string in the form `yyyy-MM-dd['T'HH:mm[:ss]]..yyyy-MM-dd['T'HH:mm[:ss]]`
 * representing an inclusive time range (e.g. `2022-05-20..2022-05-21` or
 * `2022-05-20T16:36..2022-05-20T16:37`).
 *
 * **Locator**
 *
 * A string starting with `in:` and denoting the attribute that should be
 * compared with the give term(s). See `Attributes` below for a complete list
 * of all possible attributes. Example: `in:name`
 *
 * **Type**
 *
 * A string starting with `is:` and denoting the type of documents to search.
 * Possible values are `is:workflow` and `is:processchain`.
 *
 * **Filter**
 *
 * A string that starts with an attribute name and gives a term that should
 * appear in this attribute. See `Attributes` below for a complete list
 * of all possible attributes. Example: `name:Elvis`
 *
 * **Attributes**
 *
 * Possible values for attributes are:
 *
 * * `id`
 * * `name`
 * * `error`, `errormessage`
 * * `rc`, `cap`, `reqcap`, `capability`, `requiredcapability`,
 *   `rcs`, `caps`, `reqcaps`, `capabilities`, `requiredcapabilities`
 * * `source`
 * * `start`, `startTime`
 * * `end`, `endTime`
 *
 * @author Michel Kraemer
 */
object QueryCompiler {
  private val DATE_REGEX = """(\d{4})-(\d{2})-(\d{2})""".toRegex()
  private val DATETIME_REGEX = """(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(:(\d{2}))?""".toRegex()
  private const val TIMERANGE_REGEX_PART = """(\d{4})-(\d{2})-(\d{2})(T(\d{2}):(\d{2})(:(\d{2}))?)?"""
  private val TIMERANGE_REGEX = """$TIMERANGE_REGEX_PART\.\.$TIMERANGE_REGEX_PART""".toRegex()

  // attributes
  private const val ID = "id"
  private const val NAME = "name"
  private val ERROR = listOf("error", "errormessage")
  private val RCS = listOf(
      "rc", "cap", "reqcap", "capability", "requiredcapability",
      "rcs", "caps", "reqcaps", "capabilities", "requiredcapabilities"
  )
  private const val SOURCE = "source"
  private const val STATUS = "status"
  private val START_TIME = listOf("starttime", "start")
  private val END_TIME = listOf("endtime", "end")
  private val ALL_ATTRIBUTES = listOf(ID, NAME, SOURCE, STATUS) + ERROR + RCS +
      START_TIME + END_TIME

  // types
  private const val WORKFLOW = "workflow"
  private const val PROCESSCHAIN = "processchain"

  /**
   * Compile the given [query]. Use the given [timeZone] to parse dates and times.
   */
  fun compile(query: String, timeZone: ZoneId = ZoneId.systemDefault()): Query {
    // non-breaking space (0x0a) is a reserved character used by the
    // PostgreSQLSubmissionRegistry as a separator between required capabilities
    val parts = QuotedStringSplitter.split(query.replace('\u00a0', ' '))

    val terms = mutableSetOf<Term>()
    val filters = mutableSetOf<Pair<Locator, Term>>()
    val locators = mutableSetOf<Locator>()
    val types = mutableSetOf<Type>()

    for (p in parts) {
      if (p.second) {
        // string was quoted - take it as is
        terms.add(StringTerm(p.first))
      } else {
        try {
          val colon = p.first.indexOf(":")
          if (colon > 0) {
            val key = p.first.substring(0, colon)
            val value = unquote(p.first.substring(colon + 1))
            when (key.lowercase()) {
              "in" -> locators.add(parseLocator(value))
              "is" -> types.add(parseType(value))
              in ALL_ATTRIBUTES -> filters.add(
                  parseLocator(key) to parseTerm(value, timeZone))
              else -> throw IllegalStateException()
            }
          } else {
            throw IllegalStateException()
          }
        } catch (e: IllegalStateException) {
          terms.add(parseTerm(p.first, timeZone))
        }
      }
    }

    return Query(terms = terms, filters = filters, locators = locators, types = types)
  }

  private fun unquote(str: String): String {
    return if ((str.startsWith("\"") && str.endsWith("\"")) || (str.startsWith("'") && str.endsWith("'"))) {
      StringEscapeUtils.unescapeJava(str.substring(1, str.length - 1))
    } else {
      str
    }
  }

  private fun parseTerm(term: String, timeZone: ZoneId): Term {
    val (operator, operatorLen) = if (term.startsWith("<=")) {
      Operator.LTE to 2
    } else if (term.startsWith("<")) {
      Operator.LT to 1
    } else if (term.startsWith(">=")) {
      Operator.GTE to 2
    } else if (term.startsWith(">")) {
      Operator.GT to 1
    } else {
      Operator.EQ to 0
    }

    return DATE_REGEX.matchEntire(term.substring(operatorLen))?.let { dateMatch ->
      try {
        DateTerm(LocalDate.of(
            dateMatch.groupValues[1].toInt(),
            dateMatch.groupValues[2].toInt(),
            dateMatch.groupValues[3].toInt()
        ), timeZone, operator)
      } catch (e: DateTimeException) {
        StringTerm(term)
      }
    } ?: DATETIME_REGEX.matchEntire(term.substring(operatorLen))?.let { dateTimeMatch ->
      try {
        val withSecondPrecision = dateTimeMatch.groupValues[7].isNotBlank()
        DateTimeTerm(LocalDateTime.of(
            dateTimeMatch.groupValues[1].toInt(),
            dateTimeMatch.groupValues[2].toInt(),
            dateTimeMatch.groupValues[3].toInt(),
            dateTimeMatch.groupValues[4].toInt(),
            dateTimeMatch.groupValues[5].toInt(),
            if (withSecondPrecision) dateTimeMatch.groupValues[7].toInt() else 0
        ), timeZone, withSecondPrecision, operator)
      } catch (e: DateTimeException) {
        StringTerm(term)
      }
    } ?: TIMERANGE_REGEX.matchEntire(term)?.let { timeRangeMatch ->
      try {
        val fromDate = LocalDate.of(
            timeRangeMatch.groupValues[1].toInt(),
            timeRangeMatch.groupValues[2].toInt(),
            timeRangeMatch.groupValues[3].toInt(),
        )

        val toDate = LocalDate.of(
            timeRangeMatch.groupValues[9].toInt(),
            timeRangeMatch.groupValues[10].toInt(),
            timeRangeMatch.groupValues[11].toInt(),
        )

        val (fromTime, fromWithSecondPrecision) = if (timeRangeMatch.groupValues[4].isNotBlank()) {
          val s = timeRangeMatch.groupValues[8].isNotBlank()
          LocalTime.of(
              timeRangeMatch.groupValues[5].toInt(),
              timeRangeMatch.groupValues[6].toInt(),
              if (s) timeRangeMatch.groupValues[8].toInt() else 0
          ) to s
        } else {
          null to false
        }

        val (toTime, toWithSecondPrecision) = if (timeRangeMatch.groupValues[12].isNotBlank()) {
          val s = timeRangeMatch.groupValues[16].isNotBlank()
          LocalTime.of(
              timeRangeMatch.groupValues[13].toInt(),
              timeRangeMatch.groupValues[14].toInt(),
              if (s) timeRangeMatch.groupValues[16].toInt() else 0
          ) to s
        } else {
          null to false
        }

        DateTimeRangeTerm(fromDate, fromTime, fromWithSecondPrecision,
            toDate, toTime, toWithSecondPrecision, timeZone)
      } catch (e: DateTimeException) {
        StringTerm(term)
      }
    } ?: run {
      StringTerm(term)
    }
  }

  private fun parseLocator(str: String): Locator {
    return when (str.lowercase()) {
      ID -> Locator.ID
      NAME -> Locator.NAME
      in ERROR -> Locator.ERROR_MESSAGE
      in RCS -> Locator.REQUIRED_CAPABILITIES
      in SOURCE -> Locator.SOURCE
      in STATUS -> Locator.STATUS
      in START_TIME -> Locator.START_TIME
      in END_TIME -> Locator.END_TIME
      else -> throw IllegalStateException()
    }
  }

  private fun parseType(str: String): Type {
    return when (str.lowercase()) {
      WORKFLOW -> Type.WORKFLOW
      PROCESSCHAIN -> Type.PROCESS_CHAIN
      else -> throw IllegalStateException()
    }
  }
}
