package search

import org.parboiled.Parboiled
import org.parboiled.errors.ErrorUtils
import org.parboiled.errors.ParserRuntimeException
import org.parboiled.parserunners.ReportingParseRunner
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
 * `2022-05-20T16:36:12`)
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
  /**
   * Compile the given [query]. Use the given [timeZone] to parse dates and times.
   */
  fun compile(query: String, timeZone: ZoneId = ZoneId.systemDefault()): Query {
    // non-breaking space (0x0a) is a reserved character used by the
    // PostgreSQLSubmissionRegistry as a separator between required capabilities
    val normalizedQuery = query.replace('\u00a0', ' ').trim()

    if (normalizedQuery.isEmpty()) {
      return Query()
    }

    val parser = Parboiled.createParser(QueryParser::class.java, timeZone)
    val parsingResult = ReportingParseRunner<QueryParser.QueryParserNode>(
        parser.query()).run(normalizedQuery)
    if (parsingResult.hasErrors()) {
      throw ParserRuntimeException(ErrorUtils.printParseErrors(parsingResult))
    }

    val nodes = parsingResult.valueStack.reversed().toList()
    val terms = nodes.mapNotNull { it.term }.toSet()
    val filters = nodes.mapNotNull { it.filter }.toSet()
    val locators = nodes.mapNotNull { it.locator }.toSet()
    val types = nodes.mapNotNull { it.type }.toSet()

    return Query(terms = terms, filters = filters, locators = locators, types = types)
  }
}
