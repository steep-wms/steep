package search

import model.Submission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tests for [SearchResultMatcher]
 * @author Michel Kraemer
 */
class SearchResultMatcherTest {
  @Test
  fun simple() {
    val r = SearchResult("abcdefghijklnmop", Type.WORKFLOW,
        name = "This is a sleepy name",
        errorMessage = "This is a very long error message that \n" +
            "contains line breaks \n" +
            "and that is longer than 100 characters so we can " +
            "create a shorter fragment",
        requiredCapabilities = setOf("docker", "sleep"),
        status = Submission.Status.SUCCESS.name
    )

    val m1 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("very line docker"), 100)
    assertThat(m1).isEqualTo(listOf(
        Match(
            locator = Locator.ERROR_MESSAGE,
            fragment = r.errorMessage!!.substring(0, 100) + " ...",
            termMatches = listOf(
                TermMatch("very", indices = listOf(10)),
                TermMatch("line", indices = listOf(49))
            )
        ),
        Match(
            locator = Locator.REQUIRED_CAPABILITIES,
            fragment = "docker",
            termMatches = listOf(
                TermMatch("docker", indices = listOf(0))
            )
        )
    ))

    val m2 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("that docker leep"), 100)
    assertThat(m2).isEqualTo(listOf(
        Match(
            locator = Locator.NAME,
            fragment = r.name!!,
            termMatches = listOf(
                TermMatch("leep", indices = listOf(11))
            )
        ),
        Match(
            locator = Locator.ERROR_MESSAGE,
            fragment = r.errorMessage!!.substring(0, 100) + " ...",
            termMatches = listOf(
                TermMatch("that", indices = listOf(34, 66))
            )
        ),
        Match(
            locator = Locator.REQUIRED_CAPABILITIES,
            fragment = "docker",
            termMatches = listOf(
                TermMatch("docker", indices = listOf(0))
            )
        ),
        Match(
            locator = Locator.REQUIRED_CAPABILITIES,
            fragment = "sleep",
            termMatches = listOf(
                TermMatch("leep", indices = listOf(1))
            )
        )
    ))

    val m3 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("can characters"), 100)
    assertThat(m3).isEqualTo(listOf(
        Match(
            locator = Locator.ERROR_MESSAGE,
            fragment = "... " + r.errorMessage!!.substring(40),
            termMatches = listOf(
                TermMatch("can", indices = listOf(71)),
                TermMatch("characters", indices = listOf(54))
            )
        )
    ))

    val m4 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("uccess"), 100)
    assertThat(m4).isEqualTo(listOf(
        Match(
            locator = Locator.STATUS,
            fragment = "SUCCESS",
            termMatches = listOf(
                TermMatch("uccess", indices = listOf(1))
            )
        )
    ))
  }

  @Test
  fun emptyTerm() {
    val r = SearchResult("abcdefghijklnmop", Type.WORKFLOW,
        requiredCapabilities = setOf("docker", "sleep"),
        status = Submission.Status.SUCCESS.name
    )

    val m1 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("\"\""), 100)
    assertThat(m1).isEmpty()

    val m2 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("rcs:"), 100)
    assertThat(m2).isEmpty()
  }
}
