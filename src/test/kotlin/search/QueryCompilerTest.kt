package search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Tests for the [QueryCompiler]
 * @author Michel Kraemer
 */
class QueryCompilerTest {
  @Test
  fun empty() {
    val q = QueryCompiler.compile("")
    assertThat(q).isEqualTo(Query())
  }

  @Test
  fun terms() {
    val q = QueryCompiler.compile("foo bar 5 23 a5 7z <23 >=az")
    assertThat(q).isEqualTo(Query(terms = setOf(
        StringTerm("foo"),
        StringTerm("bar"),
        StringTerm("5"),
        StringTerm("23"),
        StringTerm("a5"),
        StringTerm("7z"),
        StringTerm("<23"),
        StringTerm(">=az")
    )))
  }

  @Test
  fun dateTime() {
    val q = QueryCompiler.compile("foo 2022-05-20 2022-05-20T12:09 " +
        "2022-05-20T12:09:13 \"2022-05-20\" a2022-05-20 2022-05-20b " +
        "2022-05-20T 2022-05-20T12 2022-05-20T12: 2022-05-20T12:9 " +
        "<2022-05-30 <=2022-05-30T13:10 >=2022-05-30 >2022-05-30T13:10")
    assertThat(q).isEqualTo(Query(terms = setOf(
        StringTerm("foo"),
        DateTerm(LocalDate.of(2022, 5, 20)),
        DateTimeTerm(LocalDateTime.of(2022, 5, 20, 12, 9)),
        DateTimeTerm(LocalDateTime.of(2022, 5, 20, 12, 9, 13)),
        StringTerm("2022-05-20"),
        StringTerm("a2022-05-20"),
        StringTerm("2022-05-20b"),
        StringTerm("2022-05-20T"),
        StringTerm("2022-05-20T12"),
        StringTerm("2022-05-20T12:"),
        StringTerm("2022-05-20T12:9"),
        DateTerm(LocalDate.of(2022, 5, 30), Operator.LT),
        DateTimeTerm(LocalDateTime.of(2022, 5, 30, 13, 10), Operator.LTE),
        DateTerm(LocalDate.of(2022, 5, 30), Operator.GTE),
        DateTimeTerm(LocalDateTime.of(2022, 5, 30, 13, 10), Operator.GT)
    )))
  }

  @Test
  fun inLocator() {
    val q = QueryCompiler.compile("foo in:id in:name \"in:rcs\"")
    assertThat(q).isEqualTo(Query(
        terms = setOf(
            StringTerm("foo"),
            StringTerm("in:rcs")
        ),
        locators = setOf(
            Locator.ID,
            Locator.NAME
        )
    ))

    val q2 = QueryCompiler.compile("in:error in:rcs in:status")
    assertThat(q2).isEqualTo(Query(
        terms = emptySet(),
        locators = setOf(
            Locator.ERROR_MESSAGE,
            Locator.REQUIRED_CAPABILITIES,
            Locator.STATUS
        )
    ))

    val q3 = QueryCompiler.compile("in:\"errormessage\" in:requiredcapability in:source")
    assertThat(q3).isEqualTo(Query(
        terms = emptySet(),
        locators = setOf(
            Locator.ERROR_MESSAGE,
            Locator.REQUIRED_CAPABILITIES,
            Locator.SOURCE
        )
    ))
  }

  @Test
  fun type() {
    val q = QueryCompiler.compile("foo is:workflow")
    assertThat(q).isEqualTo(Query(
        terms = setOf(
            StringTerm("foo")
        ),
        types = setOf(
            Type.WORKFLOW
        )
    ))

    val q2 = QueryCompiler.compile("foo is:processchain")
    assertThat(q2).isEqualTo(Query(
        terms = setOf(
            StringTerm("foo")
        ),
        types = setOf(
            Type.PROCESS_CHAIN
        )
    ))
  }

  @Test
  fun filter() {
    val q = QueryCompiler.compile("foo name:elvis ID:1234 name:2022-05-20")
    assertThat(q).isEqualTo(Query(
        terms = setOf(
            StringTerm("foo")
        ),
        filters = setOf(
            Locator.NAME to StringTerm("elvis"),
            Locator.ID to StringTerm("1234"),
            Locator.NAME to DateTerm(LocalDate.of(2022, 5, 20))
        )
    ))
  }
}
