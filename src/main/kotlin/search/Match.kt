package search

/**
 * Specifies a match within a property of a found object
 * @author Michel Kraemer
 */
data class Match(
    /**
     * The property in which the match was found
     */
    val locator: Locator,

    /**
     * A fragment of the property's value (an 'excerpt' or a 'preview'). If
     * the property's value is small enough, the fragment might even contain
     * the whole value.
     */
    val fragment: String,

    /**
     * A list of matches within [fragment]
     */
    val termMatches: List<TermMatch>
)
