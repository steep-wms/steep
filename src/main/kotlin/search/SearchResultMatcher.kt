package search

import java.lang.Integer.max
import java.lang.Integer.min
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatter.ISO_INSTANT
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/**
 * Inspects a [SearchResult] and compares it with a given [Query] to create a
 * list of [Match] objects
 *
 * Some of the code has been taken from the [source of the Vert.x website](https://github.com/vertx-web-site/vertx-web-site.github.io/blob/08a502fcff557944aebbd9ebaa6b2895accbeecd/components/search/SearchPanel.jsx)
 * released under the Apache-2.0 license and converted from JavaScript to Kotlin.
 *
 * @author Michel Kraemer
 */
object SearchResultMatcher {
  private const val DEFAULT_MAX_FRAGMENT_LENGTH = 100

  private fun operatorToString(op: Operator) = when (op) {
    Operator.LT -> "<"
    Operator.LTE -> "<="
    Operator.EQ -> ""
    Operator.GTE -> ">="
    Operator.GT -> ">"
  }

  /**
   * Convert a [term] to its string representation
   */
  fun termToString(term: Term) = when (term) {
    is StringTerm -> term.value
    is DateTerm -> operatorToString(term.operator) +
        term.value.format(DateTimeFormatter.ISO_LOCAL_DATE)
    is DateTimeTerm -> {
      val r = operatorToString(term.operator) +
          term.value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      if (term.withSecondPrecision) {
        r
      } else {
        r.substring(0, r.length - 3)
      }
    }
    is DateTimeRangeTerm -> {
      val start = if (term.fromInclusiveTime != null) {
        val r = term.fromInclusiveDate.atTime(term.fromInclusiveTime)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        if (term.fromWithSecondPrecision) {
          r
        } else {
          r.substring(0, r.length - 3)
        }
      } else {
        term.fromInclusiveDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
      }
      val end = if (term.toInclusiveTime != null) {
        val r = term.toInclusiveDate.atTime(term.toInclusiveTime)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        if (term.toWithSecondPrecision) {
          r
        } else {
          r.substring(0, r.length - 3)
        }
      } else {
        term.toInclusiveDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
      }
      "$start..$end"
    }
  }

  /**
   * Search the given [propertyValue] and find all indices of all [terms]
   */
  private fun findTermMatches(propertyValue: String, terms: List<String>,
      maxMatches: Int): List<TermMatch> {
    val termMatches = mutableListOf<TermMatch>()
    for (t in terms) {
      if (t.isEmpty()) {
        continue
      }
      val indices = mutableListOf<Int>()
      var s = 0
      do {
        val i = propertyValue.indexOf(t, startIndex = s, ignoreCase = true)
        if (i >= 0) {
          indices.add(i)
        }
        s = i + t.length
      } while (s < propertyValue.length && i >= 0)

      if (indices.isNotEmpty()) {
        termMatches.add(TermMatch(t, indices))
        if (termMatches.size == maxMatches) {
          break
        }
      }
    }
    return termMatches
  }

  /**
   * Compare the given [propertyValue] representing a date/time with the
   * given [terms] and find all matches
   */
  private fun findTermMatches(propertyValue: Instant, terms: Collection<Term>): List<TermMatch> {
    val termMatches = mutableListOf<TermMatch>()
    for (t in terms) {
      val (range, operator) = when (t) {
        is DateTerm ->
          (
              t.value.atStartOfDay(t.timeZone).toInstant() to
              t.value.plusDays(1).atStartOfDay(t.timeZone).toInstant()
          ) to t.operator

        is DateTimeTerm -> {
          if (t.withSecondPrecision) {
            (
                t.value.truncatedTo(ChronoUnit.SECONDS)
                    .atZone(t.timeZone).toInstant() to
                t.value.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
                    .atZone(t.timeZone).toInstant()
            ) to t.operator
          } else {
            (
                t.value.truncatedTo(ChronoUnit.MINUTES)
                    .atZone(t.timeZone).toInstant() to
                t.value.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
                    .atZone(t.timeZone).toInstant()
            ) to t.operator
          }
        }

        is DateTimeRangeTerm -> {
          val start = (if (t.fromInclusiveTime != null) {
            (if (t.fromWithSecondPrecision) {
              t.fromInclusiveDate.atTime(t.fromInclusiveTime)
                  .truncatedTo(ChronoUnit.SECONDS)
            } else {
              t.fromInclusiveDate.atTime(t.fromInclusiveTime)
                  .truncatedTo(ChronoUnit.MINUTES)
            }).atZone(t.timeZone)
          } else {
            t.fromInclusiveDate.atStartOfDay(t.timeZone)
          }).toInstant()

          val endExclusive = (if (t.toInclusiveTime != null) {
            (if (t.toWithSecondPrecision) {
              t.toInclusiveDate.atTime(t.toInclusiveTime)
                  .truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
            } else {
              t.toInclusiveDate.atTime(t.toInclusiveTime)
                  .truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            }).atZone(t.timeZone)
          } else {
            t.toInclusiveDate.plusDays(1).atStartOfDay(t.timeZone)
          }).toInstant()

          (start to endExclusive) to Operator.EQ
        }

        else -> continue
      }

      val isMatch = when (operator) {
        Operator.LT -> propertyValue.isBefore(range.first)
        Operator.LTE -> propertyValue.isBefore(range.second)
        Operator.EQ -> propertyValue == range.first ||
            (propertyValue.isAfter(range.first) && propertyValue.isBefore(range.second))
        Operator.GTE -> propertyValue == range.first ||
            propertyValue.isAfter(range.first)
        Operator.GT -> propertyValue == range.second ||
            propertyValue.isAfter(range.second)
      }

      if (isMatch) {
        termMatches.add(TermMatch(termToString(t), emptyList()))
      }
    }
    return termMatches
  }

  /**
   * Convert the given list of [termMatches] to a list of ranges (i.e. a list
   * of start and end positions)
   */
  private fun termMatchesToRanges(termMatches: List<TermMatch>): List<Pair<Int, Int>> {
    return termMatches.flatMap { m -> m.indices.map { it to it + m.term.length }}
  }

  /**
   * Coalesce and sort the given [ranges] (i.e. recursively merge overlapping ranges)
   */
  private fun coalesceAndSortRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val sortedRanges = ranges.sortedBy { it.first }.toMutableList()

    var last = 0
    for (i in 1 until sortedRanges.size) {
      if (sortedRanges[last].second >= sortedRanges[i].first) {
        sortedRanges[last] = sortedRanges[last].copy(
            second = max(sortedRanges[last].second, sortedRanges[i].second))
      } else {
        last++
        sortedRanges[last] = sortedRanges[i]
      }
    }

    return sortedRanges.subList(0, last + 1)
  }

  /**
   * Create a fragment from a [propertyValue] with a [maximumLength]
   * around the given term [ranges]
   */
  private fun makeFragment(propertyValue: String, ranges: List<Pair<Int, Int>>,
      maximumLength: Int): Pair<Int, Int> {
    if (propertyValue.length <= maximumLength) {
      return 0 to propertyValue.length
    }

    // find longest range that does not exceed maximumLength
    val start = ranges[0].first
    var i = ranges.size
    while (i > 0) {
      --i
      if (ranges[i].second - start <= maximumLength) {
        break
      }
    }
    val end = ranges[i].second

    if (end - start >= maximumLength) {
      return start to end
    }

    // skip forward and backward
    val rest = maximumLength - (end - start)
    var newstart = start - ceil(rest / 2.0).toInt()
    var newend = end + rest / 2
    if (newstart < 0) {
      newstart = 0
      newend = maximumLength
    } else if (newend > propertyValue.length) {
      newend = propertyValue.length
      newstart = propertyValue.length - maximumLength
    }
    newstart = max(0, newstart)
    newend = min(propertyValue.length, newend)

    // trim right word
    if (newend < propertyValue.length) {
      while (newend > end && propertyValue[newend].isLetterOrDigit()) {
        --newend
        if (newstart > 0) {
          --newstart
        }
      }
    }

    // trim left word
    if (newstart > 0) {
      while (newstart < start && propertyValue[newstart - 1].isLetterOrDigit()) {
        ++newstart
      }
    }

    return newstart to newend
  }

  /**
   * Create a [Match] object for the given parameters. Put the [Match] object
   * into [matches]. If the match could not be created, do nothing.
   */
  private fun makeMatch(propertyValue: String, locator: Locator,
      filters: Map<Locator, Set<Term>>, matches: MutableList<Match>,
      maxFragmentLength: Int) {
    val terms = filters[locator]?.filterIsInstance<StringTerm>()?.map(this::termToString) ?: return
    val termMatches = findTermMatches(propertyValue, terms, maxFragmentLength)
    if (termMatches.isEmpty()) {
      return
    }

    // make fragment
    val ranges = coalesceAndSortRanges(termMatchesToRanges(termMatches))
    var (start, end) = makeFragment(propertyValue, ranges, maxFragmentLength)
    while (start < propertyValue.length && propertyValue[start].isWhitespace()) start++
    while (end > start && propertyValue[end - 1].isWhitespace()) end--
    var fragment = propertyValue.substring(start, end)
    if (start > 0) {
      fragment = "... $fragment"
    }
    if (end < propertyValue.length) {
      fragment += " ..."
    }

    // keep term matches that fall into the fragment range
    val termMatchesWithin = termMatches.mapNotNull { m ->
      val indicesWithin = m.indices.filter { it >= start && it + m.term.length <= end }
      if (indicesWithin.isEmpty()) {
        null
      } else {
        val newIndices = if (start > 0) {
          // move indices of term match to match start of fragment but
          // consider ellipsis "... " at the beginning
          indicesWithin.map { it - start + 4 }
        } else {
          indicesWithin
        }
        m.copy(indices = newIndices)
      }
    }

    matches.add(Match(locator, fragment, termMatchesWithin))
  }

  /**
   * Create a [Match] object for the given parameters. Put the [Match] object
   * into [matches]. If the match could not be created, do nothing.
   */
  private fun makeMatch(propertyValue: Instant, locator: Locator,
      filters: Map<Locator, Set<Term>>, matches: MutableList<Match>) {
    val terms = filters[locator] ?: return
    val termMatches = findTermMatches(propertyValue, terms)
    if (termMatches.isNotEmpty()) {
      matches.add(Match(locator, ISO_INSTANT.format(propertyValue), termMatches))
    }
  }

  /**
   * Inspects the given search [result] and compares it with a [query] to
   * create a list of [Match] objects containing information about term matches
   * as well as fragments of matched properties. Each fragment has a maximum
   * length of [maxFragmentLength].
   */
  fun toMatch(result: SearchResult, query: Query,
      maxFragmentLength: Int = DEFAULT_MAX_FRAGMENT_LENGTH): List<Match> {
    // collect all terms and group them by locator
    val locators = if (query.terms.isNotEmpty()) {
      query.locators.ifEmpty { Locator.values().toSet() }
    } else {
      emptyList()
    }
    val filters = mutableMapOf<Locator, MutableSet<Term>>()
    for (l in locators) {
      filters[l] = query.terms.toMutableSet()
    }
    for (f in query.filters) {
      filters.compute(f.first) { _, v -> (v ?: mutableSetOf()).also { it.add(f.second) }}
    }

    // try to find matches for each attribute of the search result object
    val matches = mutableListOf<Match>()
    makeMatch(result.id, Locator.ID, filters, matches, maxFragmentLength)
    if (result.name != null) {
      makeMatch(result.name, Locator.NAME, filters, matches, maxFragmentLength)
    }
    if (result.errorMessage != null) {
      makeMatch(result.errorMessage, Locator.ERROR_MESSAGE, filters, matches,
          maxFragmentLength)
    }
    for (rc in result.requiredCapabilities) {
      makeMatch(rc, Locator.REQUIRED_CAPABILITIES, filters, matches,
          maxFragmentLength)
    }
    if (result.source != null) {
      makeMatch(result.source, Locator.SOURCE, filters, matches,
          maxFragmentLength)
    }
    makeMatch(result.status, Locator.STATUS, filters, matches,
        maxFragmentLength)
    if (result.startTime != null) {
      makeMatch(result.startTime, Locator.START_TIME, filters, matches)
    }
    if (result.endTime != null) {
      makeMatch(result.endTime, Locator.END_TIME, filters, matches)
    }

    return matches
  }
}
