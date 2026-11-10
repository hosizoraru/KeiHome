package os.kei.hyper.home.ui.main

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PackEventTimeTest {
  private val utc = ZoneId.of("UTC")
  private val now = "2026-11-11T16:00:00Z"

  private fun compact(
    event: String,
    current: String = now,
    locale: Locale = Locale.US,
    zone: ZoneId = utc,
    use24Hour: Boolean = true,
  ) = compactPackEventTime(
    Instant.parse(event).toEpochMilli(), Instant.parse(current).toEpochMilli(),
    locale, zone, use24Hour)

  @Test fun sameDayShowsOnlyTimeWithSelectedHourCycle() {
    assertEquals("13:05", compact("2026-11-11T13:05:00Z"))
    assertEquals("1:05 PM", compact("2026-11-11T13:05:00Z", use24Hour = false))
    assertEquals("12:00 AM", compact("2026-11-11T00:00:00Z", use24Hour = false))
    assertEquals("12:00 PM", compact("2026-11-11T12:00:00Z", use24Hour = false))
    assertEquals("16:00", compact(now))
  }

  @Test fun sameYearUsesEnglishMonthWithoutYear() {
    assertEquals("Nov 4 13:05", compact("2026-11-04T13:05:00Z"))
    assertEquals("Nov 4 1:05 PM", compact("2026-11-04T13:05:00Z", use24Hour = false))
  }

  @Test fun chineseUsesLocalizedDateAndDayPeriod() {
    assertEquals("11月4日 13:05", compact("2026-11-04T13:05:00Z", locale = Locale.CHINA))
    assertEquals("下午1:05", compact("2026-11-11T13:05:00Z",
      locale = Locale.CHINA, use24Hour = false))
    assertEquals("2025年11月4日 13:05", compact("2025-11-04T13:05:00Z", locale = Locale.CHINA))
  }

  @Test fun japaneseUsesNumericMonthDayAndLocalizedDayPeriod() {
    assertEquals("11/4 13:05", compact("2026-11-04T13:05:00Z", locale = Locale.JAPAN))
    assertEquals("午後1:05", compact("2026-11-11T13:05:00Z",
      locale = Locale.JAPAN, use24Hour = false))
    assertEquals("2025/11/4 13:05", compact("2025-11-04T13:05:00Z", locale = Locale.JAPAN))
  }

  @Test fun yearBoundaryRetainsPreviousYear() {
    assertEquals("Dec 31, 2025 23:59",
      compact("2025-12-31T23:59:00Z", current = "2026-01-01T00:01:00Z"))
  }

  @Test fun dayAndYearAreComparedInRequestedZone() {
    val event = "2025-12-31T23:30:00Z"
    val current = "2026-01-01T00:30:00Z"
    assertEquals("Dec 31, 2025 23:30", compact(event, current))
    assertEquals("07:30", compact(event, current, zone = ZoneId.of("Asia/Singapore")))
    assertEquals("15:30", compact(event, current, zone = ZoneId.of("America/Los_Angeles")))
  }

  @Test fun localMidnightIncludesDateEvenWithinSameUtcDay() {
    assertEquals("Nov 11 23:59", compact("2026-11-11T15:59:00Z",
      current = "2026-11-11T16:01:00Z", zone = ZoneId.of("Asia/Singapore")))
  }

  @Test fun futureEventsAlwaysIncludeDateAndIncludeYearWhenNeeded() {
    assertEquals("Nov 11 17:00", compact("2026-11-11T17:00:00Z"))
    assertEquals("Nov 12 13:05", compact("2026-11-12T13:05:00Z"))
    assertEquals("Jan 1, 2027 00:00", compact("2027-01-01T00:00:00Z"))
  }

  @Test fun daylightSavingOverlapStillComparesLocalCalendarDay() {
    assertEquals("01:30", compact("2026-11-01T05:30:00Z",
      current = "2026-11-01T06:45:00Z", zone = ZoneId.of("America/New_York")))
  }

  @Test fun fallbackUsesLocalizedMonthAndDayOrder() {
    assertEquals("4 nov. 13:05", compact("2026-11-04T13:05:00Z", locale = Locale.FRANCE))
    assertEquals("04/11/2025 13:05", compact("2025-11-04T13:05:00Z", locale = Locale.FRANCE))
  }
}
