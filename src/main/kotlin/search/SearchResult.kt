package search

import java.time.Instant

/**
 * Result of a search performed with a [Query]. Contains information about a
 * found object. Some attributes may be `null`, which means the object either
 * does not have this attribute or the attribute was not searched because
 * other [Locator]s where specified in the [Query].
 * @author Michel Kraemer
 */
data class SearchResult(
    /**
     * The found object's ID
     */
    val id: String,

    /**
     * The found object's type
     */
    val type: Type,

    /**
     * The object's name
     */
    val name: String? = null,

    /**
     * The object's error message
     */
    val errorMessage: String? = null,

    /**
     * The required capabilities specified by the object
     */
    val requiredCapabilities: Set<String> = emptySet(),

    /**
     * The objects original source
     */
    val source: String? = null,

    /**
     * The object's status (the meaning of this string depends on the
     * object's [type])
     */
    val status: String,

    /**
     * The object's start time
     */
    val startTime: Instant? = null,

    /**
     * The object's end time
     */
    val endTime: Instant? = null
)
