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
    assertEquals(bundled, nativeSystemFallbackChoices(bundled, SystemIconFallback.DEFAULT))
    assertTrue(perfectCompletionStyles(emptyList()).isEmpty())
  }

  @Test fun allDownloadedStylesAreVisibleWithoutAnExpansionFlagWithHyperOs4First() {
    assertEquals(listOf(hyperOs4, colorOs, originOs), perfectCompletionStyles(downloaded))
    assertEquals(listOf(originOs), perfectCompletionStyles(listOf("OriginOS")))
  }

  @Test fun missingPerfectResourcesNeverRemoveTheSavedSelection() {
    assertEquals(listOf(originOs), perfectCompletionStyles(emptyList(), originOs))
    assertEquals(listOf(hyperOs4, originOs), perfectCompletionStyles(listOf("HyperOS4"), originOs))
  }

  @Test fun missingBundledResourcesAlsoKeepTheSelectedChoiceVisible() {
    assertEquals(listOf(SystemIconFallback.DEFAULT, SystemIconFallback.CLASSIC),
      nativeSystemFallbackChoices(listOf(SystemIconFallback.DEFAULT), SystemIconFallback.CLASSIC))
  }

  @Test fun duplicateAndInvalidCatalogStylesDoNotCreateInvalidChoices() {
    assertEquals(listOf(hyperOs4, originOs),
      perfectCompletionStyles(listOf("HyperOS4", "", "../style", "OriginOS", "HyperOS4", "OriginOS"),
        originOs))
  }

  @Test fun nativeChoicesNeverIncludePerfectAndStylesNeverIncludeNativeFallback() {
    assertEquals(bundled, nativeSystemFallbackChoices(bundled + originOs, originOs))
    assertEquals(listOf(hyperOs4), perfectCompletionStyles(listOf("HyperOS4"), SystemIconFallback.CLASSIC))
  }

  @Test fun enablingPrefersCurrentPerfectIntentThenSavedResourceStyle() {
    assertEquals(originOs, perfectCompletionChoice(originOs, "HyperOS4", downloaded))
    assertEquals(colorOs, perfectCompletionChoice(SystemIconFallback.CLASSIC, "ColorOS", downloaded))
  }

  @Test fun enablingUsesHyperOs4ThenFirstUsableDownloadedStyle() {
    assertEquals(hyperOs4, perfectCompletionChoice(SystemIconFallback.DEFAULT, "not-downloaded",
      listOf("OriginOS", "HyperOS4", "ColorOS")))
    assertEquals(originOs, perfectCompletionChoice(SystemIconFallback.DEFAULT, "../invalid",
      listOf("", "../invalid", "OriginOS", "ColorOS")))
  }

  @Test fun enablingRefusesNoUsableLocalResourcesDespiteSavedIntent() {
    assertNull(perfectCompletionChoice(originOs, "HyperOS4", emptyList()))
    assertNull(perfectCompletionChoice(SystemIconFallback.DEFAULT, "HyperOS4", listOf("", "../invalid")))
  }

  @Test fun explicitStyleSelectionRequiresThatExactDownloadedStyle() {
    assertNull(perfectCompletionChoice(hyperOs4, "HyperOS4", listOf("HyperOS4"), requested = originOs))
    assertEquals(originOs,
      perfectCompletionChoice(hyperOs4, "HyperOS4", listOf("OriginOS"), requested = originOs))
    assertNull(perfectCompletionChoice(hyperOs4, "HyperOS4", downloaded,
      requested = SystemIconFallback.DEFAULT))
  }
}
