package search

import java.lang.Integer.max
import java.lang.Integer.min
import java.time.format.DateTimeFormatter
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
  const val DEFAULT_MAX_FRAGMENT_LENGTH = 100

  /**
   * Convert a [term] to its string representation
   */
  private fun termToString(term: Term) = when (term) {
    is StringTerm -> term.value
    is DateTerm -> term.value.format(DateTimeFormatter.ISO_LOCAL_DATE)
    is DateTimeTerm -> term.value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
  }

  /**
   * Search the given [propertyValue] and find all indices of all [terms]
   */
  private fun findTermMatches(propertyValue: String, terms: List<String>): List<TermMatch> {
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
   * Coalesce the given [ranges] (i.e. recursively merge overlapping ranges)
   */
  private fun coalesceRanges(ranges: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()
    var anymerged = false
    for (p in ranges) {
      var merged = false
      for ((i, r) in result.withIndex()) {
        if ((p.first >= r.first && p.first <= r.second) ||
            (p.second >= r.first && p.second <= r.second)) {
          val nf = min(r.first, p.first)
          val ns = max(r.second, p.second)
          result[i] = nf to ns
          merged = true
          anymerged = true
          break
        }
      }
      if (!merged) {
        result.add(p)
      }
    }
    if (anymerged) {
      return coalesceRanges(result)
    }
    return result
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
    val terms = filters[locator]?.map(this::termToString) ?: return
    val termMatches = findTermMatches(propertyValue, terms)
    if (termMatches.isEmpty()) {
      return
    }

    // make fragment
    val ranges = coalesceRanges(termMatchesToRanges(termMatches)).sortedBy { it.first }
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

    return matches
  }
}
