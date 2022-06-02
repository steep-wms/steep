package search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

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
  fun quotedTerms() {
    val q = QueryCompiler.compile("\"foo\" 'bar' \"heLLo wOrld\" " +
        "\"fo\\\"o\" 'b\\'ar' don't ' \"bla")
    assertThat(q).isEqualTo(Query(terms = setOf(
        StringTerm("foo"),
        StringTerm("bar"),
        StringTerm("heLLo wOrld"),
        StringTerm("fo\"o"),
        StringTerm("b'ar"),
        StringTerm("don't"),
        StringTerm("'"),
        StringTerm("\"bla")
    )))
  }

  @Test
  fun dateTime() {
    val q = QueryCompiler.compile("foo 2022-05-20 2022-05-20T12:09 " +
        "2022-05-20T12:09:13 \"2022-05-20\" a2022-05-20 2022-05-20b " +
        "2022-05-20T 2022-05-20T12 2022-05-20T12: 2022-05-20T12:9 " +
        "<2022-05-30 <=2022-05-30T13:10 >=2022-05-30 >2022-05-30T13:10:00")
    assertThat(q).isEqualTo(Query(terms = setOf(
        StringTerm("foo"),
        DateTerm(LocalDate.of(2022, 5, 20)),
        DateTimeTerm(LocalDateTime.of(2022, 5, 20, 12, 9), withSecondPrecision = false),
        DateTimeTerm(LocalDateTime.of(2022, 5, 20, 12, 9, 13), withSecondPrecision = true),
        StringTerm("2022-05-20"),
        StringTerm("a2022-05-20"),
        StringTerm("2022-05-20b"),
        StringTerm("2022-05-20T"),
        StringTerm("2022-05-20T12"),
        StringTerm("2022-05-20T12:"),
        StringTerm("2022-05-20T12:9"),
        DateTerm(LocalDate.of(2022, 5, 30), operator = Operator.LT),
        DateTimeTerm(LocalDateTime.of(2022, 5, 30, 13, 10), withSecondPrecision = false,
            operator = Operator.LTE),
        DateTerm(LocalDate.of(2022, 5, 30), operator = Operator.GTE),
        DateTimeTerm(LocalDateTime.of(2022, 5, 30, 13, 10, 0), withSecondPrecision = true,
            operator = Operator.GT)
    )))
  }

  @Test
  fun invalidDateTime() {
    val q = QueryCompiler.compile("foo 2022-05-40 2022-13-01T12:01 2022-05-30T40:01")
    assertThat(q).isEqualTo(Query(terms = setOf(
        StringTerm("foo"),
        StringTerm("2022-05-40"),
        StringTerm("2022-13-01T12:01"),
        StringTerm("2022-05-30T40:01")
    )))
  }

  @Test
  fun timeRange() {
    val q = QueryCompiler.compile("foo 2022-05-20..2022-05-21 " +
        "2022-05-20T12:05..2022-05-21T13:10 " +
        "2022-05-20T12:05:09..2022-05-21T13:10:01 " +
        "2022-05-20..2022-05-21T13:10:01 " +
        "2022-05-20..2022-05-21T13:10 " +
        "2022-05-20T12:05:09..2022-05-21 " +
        "2022-05-20T12:05..2022-05-21 " +
        "start:2022-05-20..2022-05-21")
    assertThat(q).isEqualTo(Query(terms = setOf(
        StringTerm("foo"),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), null, false,
            LocalDate.of(2022, 5, 21), null, false),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), LocalTime.of(12, 5), false,
            LocalDate.of(2022, 5, 21), LocalTime.of(13, 10), false),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), LocalTime.of(12, 5, 9), true,
            LocalDate.of(2022, 5, 21), LocalTime.of(13, 10, 1), true),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), null, false,
            LocalDate.of(2022, 5, 21), LocalTime.of(13, 10, 1), true),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), null, false,
            LocalDate.of(2022, 5, 21), LocalTime.of(13, 10), false),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), LocalTime.of(12, 5, 9), true,
            LocalDate.of(2022, 5, 21), null, false),
        DateTimeRangeTerm(LocalDate.of(2022, 5, 20), LocalTime.of(12, 5), false,
            LocalDate.of(2022, 5, 21), null, false)
    ), filters = setOf(
        Locator.START_TIME to DateTimeRangeTerm(LocalDate.of(2022, 5, 20), null, false,
            LocalDate.of(2022, 5, 21), null, false)
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
    val q = QueryCompiler.compile("foo name:elvis ID:1234 name:2022-05-20 " +
        "startTime:>2022-05-30 endTime:2022-05-30 name:\"Hello World\"")
    assertThat(q).isEqualTo(Query(
        terms = setOf(
            StringTerm("foo")
        ),
        filters = setOf(
            Locator.NAME to StringTerm("elvis"),
            Locator.ID to StringTerm("1234"),
            Locator.NAME to DateTerm(LocalDate.of(2022, 5, 20)),
            Locator.START_TIME to DateTerm(LocalDate.of(2022, 5, 30), operator = Operator.GT),
            Locator.END_TIME to DateTerm(LocalDate.of(2022, 5, 30)),
            Locator.NAME to StringTerm("Hello World")
        )
    ))
  }

  @Test
  fun order() {
    val expectedResult = Query(
        terms = setOf(
            StringTerm("foo")
        ),
        locators = setOf(
            Locator.STATUS
        ),
        types = setOf(
            Type.WORKFLOW
        )
    )

    val q1 = QueryCompiler.compile("foo in:status is:workflow")
    assertThat(q1).isEqualTo(expectedResult)

    val q2 = QueryCompiler.compile("foo is:workflow in:status")
    assertThat(q2).isEqualTo(expectedResult)

    val q3 = QueryCompiler.compile("is:workflow foo in:status")
    assertThat(q3).isEqualTo(expectedResult)

    val q4 = QueryCompiler.compile("is:workflow in:status foo")
    assertThat(q4).isEqualTo(expectedResult)

    val q5 = QueryCompiler.compile("in:status is:workflow foo")
    assertThat(q5).isEqualTo(expectedResult)

    val q6 = QueryCompiler.compile("in:status foo is:workflow")
    assertThat(q6).isEqualTo(expectedResult)
  }
}
