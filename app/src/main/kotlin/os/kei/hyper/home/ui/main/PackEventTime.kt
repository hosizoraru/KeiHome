package os.kei.hyper.home.ui.main

import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

internal fun compactPackEventTime(
  millis: Long,
  nowMillis: Long,
  locale: Locale,
  zone: ZoneId,
  use24Hour: Boolean,
): String {
  val event = Instant.ofEpochMilli(millis).atZone(zone)
  val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
  val timePattern = when {
    use24Hour -> "HH:mm"
    locale.language in setOf("zh", "ja", "ko") -> "ah:mm"
    else -> "h:mm a"
  }
  val time = event.format(DateTimeFormatter.ofPattern(timePattern, locale))
  // Future timestamps retain their date, even when they fall on the same local day.
  if (millis <= nowMillis && event.toLocalDate() == now.toLocalDate()) return time

  val sameYear = event.year == now.year
  val dateFormatter = when (locale.language) {
    "zh" -> DateTimeFormatter.ofPattern(if (sameYear) "M月d日" else "uuuu年M月d日", locale)
    "en" -> DateTimeFormatter.ofPattern(if (sameYear) "MMM d" else "MMM d, uuuu", locale)
    "ja" -> DateTimeFormatter.ofPattern(if (sameYear) "M/d" else "uuuu/M/d", locale)
    else -> {
      if (sameYear) {
        val shortPattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
          FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        val dayFirst = shortPattern.indexOf('d') < shortPattern.indexOf('M')
        DateTimeFormatter.ofPattern(if (dayFirst) "d MMM" else "MMM d", locale)
      } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
      }
    }
  }
  return "${event.format(dateFormatter)} $time"
}
