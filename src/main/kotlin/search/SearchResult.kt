package search

/**
 * Result of a search performed with a [Query]
 * @author Michel Kraemer
 */
data class SearchResult(
    val id: String? = null,
    val name: String? = null,
    val errorMessage: String? = null,
    val requiredCapabilities: Set<String>? = null,
    val type: Type
)
