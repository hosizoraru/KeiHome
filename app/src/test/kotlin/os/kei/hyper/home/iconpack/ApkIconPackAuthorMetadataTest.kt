package os.kei.hyper.home.iconpack

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ApkIconPackAuthorMetadataTest {
  @Test fun cusconExplicitStringsResolveWithFixedPrecedence() {
    // Cuscon 4.1.2.3 installed on emulator-5580 declares all four as Yazazuyo.
    for (key in listOf("theme_author", "authorName", "developer_name", "developerName")) {
      assertEquals("Yazazuyo", read(mapOf(key to " Yazazuyo ")))
    }
    assertEquals("Theme author", read(mapOf("theme_author" to "Theme author", "authorName" to "Other")))
  }

  @Test fun creatorFallbackReceivesDeclaredTitleAndPreservesResolvedName() {
    assertEquals("Onno van den Dungen", ApkIconPackAuthorMetadata.read(
      mapOf("about_title" to " Arcticons ")::get,
      { title ->
        assertEquals("Arcticons", title)
        "Onno van den Dungen"
      },
    ))
    assertEquals("Declared author", ApkIconPackAuthorMetadata.read(
      mapOf("theme_author" to "Declared author", "about_title" to "Arcticons")::get,
      { throw AssertionError("Explicit author must take precedence over XML") },
    ))
  }

  @Test fun absentOrInvalidTitleDoesNotInvokeCreatorReader() {
    for (value in listOf(null, "", "  ", "a".repeat(121), "bad\nname")) {
      assertNull(ApkIconPackAuthorMetadata.read(
        { key -> value.takeIf { key == "about_title" } },
        { throw AssertionError("Invalid title must not trigger XML parsing") },
      ))
    }
  }

  @Test fun arbitraryLabelsCreditsAndPublisherLikeStringsNeverSupplyAnAuthor() {
    val keys = mutableListOf<String>()
    assertNull(ApkIconPackAuthorMetadata.read(readString = { key ->
      keys += key
      mapOf("app_name" to "Pack", "name" to "Publisher", "about_dashboard_dev" to "Dani",
        "about_desc" to "Created by Someone")[key]
    }))
    assertEquals(listOf("theme_author", "authorName", "developer_name", "developerName", "about_title"), keys)
  }

  @Test fun blanksDefaultsOversizeAndControlsAreUnknownAndCanFallThrough() {
    for (value in listOf("", "  ", "Developer", "developer", "a".repeat(121), "bad\nname", "bad\u0000name")) {
      assertNull(read(mapOf("theme_author" to value)))
      assertEquals("Actual author", read(mapOf("theme_author" to value, "authorName" to "Actual author")))
    }
    assertEquals("\u4f5c\u8005", read(mapOf("theme_author" to "\u4f5c\u8005")))
  }

  @Test fun missingOrBrokenOptionalResourcesDoNotFailThePackButCancellationPropagates() {
    assertNull(ApkIconPackAuthorMetadata.read(readString = { error("Missing resource") }))
    assertEquals("Author", ApkIconPackAuthorMetadata.read(readString = {
      if (it == "theme_author") error("Broken alias") else "Author"
    }))
    assertNull(ApkIconPackAuthorMetadata.read({ key -> "Arcticons".takeIf { key == "about_title" } },
      { error("Broken XML") }))
    assertThrows(CancellationException::class.java) {
      ApkIconPackAuthorMetadata.read(readString = { throw CancellationException() })
    }
    assertThrows(CancellationException::class.java) {
      ApkIconPackAuthorMetadata.read(readString = { null }, check = { throw CancellationException() })
    }
  }

  private fun read(strings: Map<String, String>): String? =
    ApkIconPackAuthorMetadata.read(strings::get)
}
