package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback

class SystemFallbackChoicesTest {
  private val bundled = SystemIconFallback.entries
  private val hyperOs4 = SystemIconFallback.perfect(SystemIconFallback.DEFAULT_PERFECT_STYLE)
  private val originOs = SystemIconFallback.perfect("OriginOS")
  private val colorOs = SystemIconFallback.perfect("ColorOS")
  private val downloaded = listOf("ColorOS", SystemIconFallback.DEFAULT_PERFECT_STYLE, "OriginOS")

  @Test fun emptyLocalCatalogKeepsOnlyBundledChoices() {
    assertEquals(bundled, systemFallbackChoices(bundled, emptyList(), false, SystemIconFallback.DEFAULT))
    assertEquals(bundled, systemFallbackChoices(bundled, emptyList(), true, SystemIconFallback.DEFAULT))
  }

  @Test fun collapsedListAddsOnlyDownloadedHyperOs4() {
    assertEquals(bundled + hyperOs4,
      systemFallbackChoices(bundled, downloaded, false, SystemIconFallback.DEFAULT))
    assertEquals(bundled, systemFallbackChoices(bundled, listOf("OriginOS"), false, SystemIconFallback.DEFAULT))
  }

  @Test fun expandedListShowsEveryDownloadedStyleWithHyperOs4First() {
    assertEquals(bundled + listOf(hyperOs4, colorOs, originOs),
      systemFallbackChoices(bundled, downloaded, true, SystemIconFallback.DEFAULT))
  }

  @Test fun collapsingRetainsOnlyTheCurrentAdditionalStyle() {
    assertEquals(bundled + listOf(hyperOs4, originOs),
      systemFallbackChoices(bundled, downloaded, false, originOs))
    assertEquals(bundled + listOf(hyperOs4, colorOs),
      systemFallbackChoices(bundled, downloaded, false, colorOs))
    assertEquals(bundled + hyperOs4, systemFallbackChoices(bundled, downloaded, false, hyperOs4))
  }

  @Test fun missingPerfectResourcesNeverRemoveTheSavedSelection() {
    for (expanded in listOf(false, true)) {
      assertEquals(bundled + originOs, systemFallbackChoices(bundled, emptyList(), expanded, originOs))
      assertEquals(bundled + listOf(hyperOs4, originOs),
        systemFallbackChoices(bundled, listOf("HyperOS4"), expanded, originOs))
    }
  }

  @Test fun missingBundledResourcesAlsoKeepTheSelectedChoiceVisible() {
    assertEquals(listOf(SystemIconFallback.DEFAULT, SystemIconFallback.CLASSIC),
      systemFallbackChoices(listOf(SystemIconFallback.DEFAULT), emptyList(), false, SystemIconFallback.CLASSIC))
  }

  @Test fun duplicateAndInvalidCatalogStylesDoNotCreateInvalidChoices() {
    assertEquals(bundled + listOf(hyperOs4, originOs),
      systemFallbackChoices(bundled, listOf("HyperOS4", "", "../style", "OriginOS", "HyperOS4", "OriginOS"),
        true, originOs))
  }

  @Test fun resourceEntryOpensDownloadPageWithoutTheRelevantLocalStyle() {
    assertEquals("perfect", perfectFallbackResourceRoute(SystemIconFallback.DEFAULT, emptyList()))
    assertEquals("perfect", perfectFallbackResourceRoute(SystemIconFallback.DEFAULT, listOf("OriginOS")))
    assertEquals("perfect", perfectFallbackResourceRoute(originOs, listOf("HyperOS4")))
  }

  @Test fun resourceEntryOpensSourceInfoForTheRelevantDownloadedStyle() {
    assertEquals("perfect-sources",
      perfectFallbackResourceRoute(SystemIconFallback.DEFAULT, listOf("HyperOS4")))
    assertEquals("perfect-sources", perfectFallbackResourceRoute(originOs, listOf("OriginOS")))
    assertEquals("perfect-sources", perfectFallbackResourceRoute(hyperOs4, listOf("HyperOS4")))
  }
}
