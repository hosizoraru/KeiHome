package os.kei.hyper.home.iconpack

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NativeMamlCalendarPairTest {
  private fun source() = requireNotNull(javaClass.getResourceAsStream("/native-maml/calendar-pair.xml"))
    .use { it.readBytes().toString(Charsets.UTF_8) }

  @Test fun recognizesCapturedClosedCalendarAndEnumeratesExactlySixtyTwoLocalImages() {
    val bytes = source().toByteArray()
    val original = bytes.copyOf()
    val pair = requireNotNull(NativeMamlCalendarPair.parse(bytes))
    assertEquals("calendar_0/themed_icon_calendar.png", pair.light)
    assertEquals("calendar_1/themed_icon_calendar.png", pair.dark)
    assertEquals((0..1).flatMap { mode ->
      (1..31).map { "calendar_$mode/themed_icon_calendar_$it.png" }
    }, pair.images)
    assertEquals(62, pair.images.toSet().size)
    assertArrayEquals(original, bytes)
    // Admission of a calendar does not misrepresent it as a static preview pair.
    assertNull(NativeMamlPair.parse(bytes))
  }

  @Test fun rejectsAnyOtherUpdaterSelectorExpressionPathOrProgram() {
    val xml = source()
    val source = "calendar_0/themed_icon_calendar.png"
    val mutations = listOf(
      xml.replace("DateTime.Day", "none"),
      xml.replace("DateTime.Day", "DateTime.Second"),
      xml.replace("srcid=\"#date\"", "srcid=\"#date+1\""),
      xml.replace("srcid=\"#date\"", "srcid=\"0\""),
      xml.replace("srcid=\"#date\"", ""),
      xml.replace("srcid=\"#date\"", "srcExp=\"@image\""),
      xml.replace("calendar_1/themed_icon_calendar.png", source),
      xml.replace("frameRate=\"60\"", "frameRate=\"120\""),
      xml.replace("alpha=\"255-#__darkmode*255\"", "alpha=\"#date\""),
      xml.replace("command=\"to\"", "command=\"setTo\""),
      xml.replace("</Icon>", "<IntentCommand action='android.intent.action.VIEW'/></Icon>"),
      xml.replace("</Icon>", "<UnknownCommand/></Icon>"),
      xml.replace("</Icon>", "<Image src='extra.png' srcid='#date'/></Icon>"),
      xml.replace("<ExternalCommands>", "<ExternalCommands>unexpected"),
    ) + listOf(
      "../outside.png", "calendar_0/../outside.png", "/outside.png", "calendar_0//a.png",
      "calendar_0/./a.png", "file:/outside.png", "content://icons/a.png", "https://host/a.png",
      "calendar_0\\a.png", "calendar_0/%2e%2e/a.png", "@image.png", "#date.png",
      "calendar_0/a.webp", "calendar_0/a.png?day=#date",
    ).map { xml.replace(source, it) }
    for (changed in mutations) {
      assertNull("Must reject altered calendar: $changed",
        NativeMamlCalendarPair.parse(changed.toByteArray()))
    }
    val oneSelector = xml.replaceFirst("srcid=\"#date\"", "srcid=\"1\"")
    assertNull(NativeMamlCalendarPair.parse(oneSelector.toByteArray()))
  }

  @Test fun canonicalDayNightFormStillRequiresTheEntireExistingClosedCrossfadeProof() {
    val canonical = source().replace("DateTime.Day", "none")
      .replace("calendar_0/themed_icon_calendar.png", "day.png")
      .replace("calendar_1/themed_icon_calendar.png", "night.png")
      .replace("srcid=\"#date\"", "")
    assertEquals(NativeMamlPair("day.png", "night.png"), NativeMamlPair.parse(canonical.toByteArray()))
    for (changed in listOf(
      canonical.replace("none", "DateTime.Day"),
      canonical.replace("night.png", "../night.png"),
      canonical.replace("frameRate=\"60\"", "frameRate=\"120\""),
      canonical.replace("</Icon>", "<UnknownCommand/></Icon>"),
    )) assertNull(NativeMamlPair.parse(changed.toByteArray()))
  }

  @Test fun retainsXmlBudgetStructureLimitsAndCancellation() {
    assertThrows(IconPackException::class.java) {
      NativeMamlCalendarPair.parse((source() + " ".repeat(64 * 1024)).toByteArray())
    }
    val tooManyElements = source().replace("</Icon>", "${"<Unknown/>".repeat(40)}</Icon>")
    assertNull(NativeMamlCalendarPair.parse(tooManyElements.toByteArray()))
    assertThrows(IconPackException::class.java) {
      NativeMamlCalendarPair.parse("<!DOCTYPE Icon [<!ENTITY e 'x'>]><Icon/>".toByteArray())
    }
    assertThrows(CancellationException::class.java) {
      NativeMamlCalendarPair.parse(source().toByteArray()) { throw CancellationException() }
    }
  }
}
