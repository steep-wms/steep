package search

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * An object type to search for and its [priority] in the search results.
 * Objects will be sorted by priority in ascending order.
 */
enum class Type(val priority: Int) {
  WORKFLOW(0),
  PROCESS_CHAIN(1)
}

/**
 * A location that should be searched
 */
enum class Locator {
  ERROR_MESSAGE,
  ID,
  NAME,
  REQUIRED_CAPABILITIES
}

/**
 * Search terms
 */
sealed interface Term
data class StringTerm(val value: String) : Term
data class DateTerm(val value: LocalDate) : Term
data class DateTimeTerm(val value: LocalDateTime) : Term

/**
 * A search query
 */
data class Query(
    val terms: Set<Term> = emptySet(),
    val filters: Set<Pair<Locator, Term>> = emptySet(),
    val locators: Set<Locator> = emptySet(),
    val types: Set<Type> = emptySet()
)
