package search

import helper.UniqueID
import model.Submission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter.ISO_INSTANT

/**
 * Tests for [SearchResultMatcher]
 * @author Michel Kraemer
 */
class SearchResultMatcherTest {
  @Test
  fun simple() {
    val startTime = LocalDateTime.of(2022, 5, 31, 7, 45)
        .atZone(ZoneId.systemDefault()).toInstant()
    val r = SearchResult("abcdefghijklnmop", Type.WORKFLOW,
        name = "This is a sleepy name",
        errorMessage = "This is a very long error message that \n" +
            "contains line breaks \n" +
            "and that is longer than 100 characters so we can " +
            "create a shorter fragment",
        requiredCapabilities = setOf("docker", "sleep"),
        status = Submission.Status.SUCCESS.name,
        startTime = startTime
    )

    val m1 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("very line docker"),
        maxFragmentLength = 100)
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

    val m2 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("that docker leep"),
        maxFragmentLength = 100)
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

    val m3 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("can characters"),
        maxFragmentLength = 100)
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

    val m4 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("uccess"),
        maxFragmentLength = 100)
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

    val m1 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("\"\""))
    assertThat(m1).isEmpty()

    val m2 = SearchResultMatcher.toMatch(r, QueryCompiler.compile("rcs:"))
    assertThat(m2).isEmpty()
  }

  @Test
  fun dateTime() {
    val startTime = LocalDateTime.of(2022, 5, 31, 7, 45)
        .atZone(ZoneId.systemDefault()).toInstant()
    val endTime = LocalDateTime.of(2022, 5, 31, 7, 55)
        .atZone(ZoneId.systemDefault()).toInstant()
    val r = SearchResult(UniqueID.next(), Type.WORKFLOW,
        status = "SUCCESS",
        name = "2022-05-31",
        startTime = startTime,
        endTime = endTime
    )

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile("2022-05-31"))
      assertThat(m).isEqualTo(listOf(
          Match(
              locator = Locator.START_TIME,
              fragment = ISO_INSTANT.format(startTime),
              termMatches = listOf(
                  TermMatch("2022-05-31", indices = emptyList())
              )
          ),
          Match(
              locator = Locator.END_TIME,
              fragment = ISO_INSTANT.format(endTime),
              termMatches = listOf(
                  TermMatch("2022-05-31", indices = emptyList())
              )
          )
      ))
    }

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile("<2022-05-31"))
      assertThat(m).isEmpty()
    }

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile(">2022-05-31"))
      assertThat(m).isEmpty()
    }

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile("<=2022-05-31T07:45"))
      assertThat(m).isEqualTo(listOf(
          Match(
              locator = Locator.START_TIME,
              fragment = ISO_INSTANT.format(startTime),
              termMatches = listOf(
                  TermMatch("<=2022-05-31T07:45", indices = emptyList())
              )
          )
      ))
    }

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile(">=2022-05-31T07:55:00"))
      assertThat(m).isEqualTo(listOf(
          Match(
              locator = Locator.END_TIME,
              fragment = ISO_INSTANT.format(endTime),
              termMatches = listOf(
                  TermMatch(">=2022-05-31T07:55:00", indices = emptyList())
              )
          )
      ))
    }

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile("2022-05-31..2022-06-01"))
      assertThat(m).isEqualTo(listOf(
          Match(
              locator = Locator.START_TIME,
              fragment = ISO_INSTANT.format(startTime),
              termMatches = listOf(
                  TermMatch("2022-05-31..2022-06-01", indices = emptyList())
              )
          ),
          Match(
              locator = Locator.END_TIME,
              fragment = ISO_INSTANT.format(endTime),
              termMatches = listOf(
                  TermMatch("2022-05-31..2022-06-01", indices = emptyList())
              )
          )
      ))
    }

    run {
      val m = SearchResultMatcher.toMatch(r, QueryCompiler.compile("2022-05-31T07:54..2022-06-01T07:55:05"))
      assertThat(m).isEqualTo(listOf(
          Match(
              locator = Locator.END_TIME,
              fragment = ISO_INSTANT.format(endTime),
              termMatches = listOf(
                  TermMatch("2022-05-31T07:54..2022-06-01T07:55:05", indices = emptyList())
              )
          )
      ))
    }
  }
}
