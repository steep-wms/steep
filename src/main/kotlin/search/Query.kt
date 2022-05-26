package search

import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * An object type to search for and its [priority] in the search results.
 * Objects will be sorted by priority in ascending order.
 */
enum class Type(val priority: Int, @JsonValue val type: String) {
  WORKFLOW(0, "workflow"),
  PROCESS_CHAIN(1, "processChain")
}

/**
 * A location that should be searched
 */
enum class Locator(@JsonValue val propertyName: String) {
  ERROR_MESSAGE("errorMessage"),
  ID("id"),
  NAME("name"),
  REQUIRED_CAPABILITIES("requiredCapabilities"),
  SOURCE("source"),
  STATUS("status")
  // startTime
  // endTime
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
