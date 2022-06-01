package search

import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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
  STATUS("status"),
  START_TIME("startTime"),
  END_TIME("endTime")
}

/**
 * Comparison operators
 */
enum class Operator {
  /**
   * Less than
   */
  LT,

  /**
   * Less than or equal to
   */
  LTE,

  /**
   * Equal to
   */
  EQ,

  /**
   * Greater than or equal to
   */
  GTE,

  /**
   * Greater than
   */
  GT
}

/**
 * Search terms
 */
sealed interface Term
data class StringTerm(val value: String) : Term
data class DateTerm(val value: LocalDate,
    val timeZone: ZoneId = ZoneId.systemDefault(),
    val operator: Operator = Operator.EQ
) : Term
data class DateTimeTerm(val value: LocalDateTime,
    val timeZone: ZoneId = ZoneId.systemDefault(),
    val withSecondPrecision: Boolean = true,
    val operator: Operator = Operator.EQ
) : Term
data class DateTimeRangeTerm(
    val fromInclusiveDate: LocalDate,
    val fromInclusiveTime: LocalTime?,
    val fromWithSecondPrecision: Boolean,
    val toInclusiveDate: LocalDate,
    val toInclusiveTime: LocalTime?,
    val toWithSecondPrecision: Boolean,
    val timeZone: ZoneId = ZoneId.systemDefault()
) : Term

/**
 * A search query
 */
data class Query(
    val terms: Set<Term> = emptySet(),
    val filters: Set<Pair<Locator, Term>> = emptySet(),
    val locators: Set<Locator> = emptySet(),
    val types: Set<Type> = emptySet()
)
