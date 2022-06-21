package search

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Specifies the locations of a matched term within a property of a found object
 * @author Michel Kraemer
 */
data class TermMatch(
    /**
     * The matched term
     */
    val term: String,

    /**
     * The start positions at which the term was found (relative to [Match.fragment])
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val indices: List<Int> = emptyList()
)
