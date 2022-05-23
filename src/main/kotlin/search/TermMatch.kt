package search

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
     * The start positions at which the term was found
     */
    val indices: List<Int>
)
