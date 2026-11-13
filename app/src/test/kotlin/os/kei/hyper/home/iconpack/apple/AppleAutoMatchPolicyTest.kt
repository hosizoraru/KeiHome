package os.kei.hyper.home.iconpack.apple

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleAutoMatchPolicyTest {
  @Test fun explicitRetriesBypassOnlySuccessfulSearchHistory() {
    val now = 1_800_000_000_000L
    for (status in AppleMatchAttemptStatus.entries) {
      val attempt = AppleMatchAttempt(status, now - 1000, now + 60_000, "cn")
      assertEquals(false, AppleAutoMatchPolicy.canAttempt(attempt, now, explicit = false))
      assertEquals(status != AppleMatchAttemptStatus.FAILED,
        AppleAutoMatchPolicy.canAttempt(attempt, now, explicit = true))
      assertTrue(AppleAutoMatchPolicy.canAttempt(attempt, attempt.retryAt, explicit = false))
    }
    assertTrue(AppleAutoMatchPolicy.canAttempt(null, now, explicit = true))
    assertTrue(AppleAutoMatchPolicy.canAttempt(null, now, explicit = false))
  }

  private fun target(label: String, pkg: String = "android.example.app") =
    AppleAppTarget(pkg, label, system = false)

  private fun app(
    name: String,
    id: String = "123456789",
    country: String? = "cn",
    bundle: String = "ios.example.app",
  ) = AppleAppMatch(id, name, "Example publisher", bundle, country)

  @Test fun exactNormalizedNamesMatchRegardlessOfSystemFlag() {
    val candidate = app("Focus: Timer")
    for (system in listOf(false, true)) {
      assertSame(candidate, AppleAutoMatchPolicy.choose(
        target("  FOCUS-TIMER  ").copy(system = system), listOf(app("Other"), candidate)))
    }
  }

  @Test fun fullwidthCompatibilityAndCanonicalAccentsNormalize() {
    val fullwidth = app("FOO 123")
    assertSame(fullwidth, AppleAutoMatchPolicy.choose(target("Ｆｏｏ　１２３"), listOf(fullwidth)))
    val accented = app("Caf\u00e9")
    assertSame(accented, AppleAutoMatchPolicy.choose(target("Cafe\u0301"), listOf(accented)))
    assertNull(AppleAutoMatchPolicy.choose(target("Cafe"), listOf(accented)))
  }

  @Test fun cjkNamesAndBenignPunctuationMatchWithoutTranslation() {
    val chinese = app("天气预报")
    assertSame(chinese, AppleAutoMatchPolicy.choose(target("天 气，预报！"), listOf(chinese)))
    val japanese = app("メモ帳")
    assertSame(japanese, AppleAutoMatchPolicy.choose(target("「メモ・帳」"), listOf(japanese)))
    assertNull(AppleAutoMatchPolicy.choose(target("天气预报"), listOf(app("Weather Forecast"))))
    val supplementary = app("\ud840\udc00文")
    assertSame(supplementary, AppleAutoMatchPolicy.choose(target("\ud840\udc00 文"), listOf(supplementary)))
  }

  @Test fun caseFoldingDoesNotDependOnTurkishDefaultLocale() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      val candidate = app("TIMER")
      assertSame(candidate, AppleAutoMatchPolicy.choose(target("timer"), listOf(candidate)))
    } finally {
      Locale.setDefault(previous)
    }
  }

  @Test fun editionsAreNotStrippedAndSubstringsNeverMatch() {
    val base = app("Reader")
    for (edition in listOf("Reader Pro", "Reader HD", "Reader Lite", "Reader Plus",
      "Reader Premium", "Reader 2", "SuperReader", "Reader for iPad")) {
      assertNull(edition, AppleAutoMatchPolicy.choose(target(edition), listOf(base)))
      assertNull(edition, AppleAutoMatchPolicy.choose(target("Reader"), listOf(app(edition))))
    }
    val pro = app("Reader Pro")
    assertSame(pro, AppleAutoMatchPolicy.choose(target("Reader (Pro)"), listOf(pro)))
  }

  @Test fun meaningfulSymbolsAreNotRemoved() {
    for (name in listOf("C++", "C#", "A&B", "100%")) {
      val candidate = app(name)
      assertSame(candidate, AppleAutoMatchPolicy.choose(target(name), listOf(candidate)))
    }
    assertNull(AppleAutoMatchPolicy.choose(target("C++"), listOf(app("C"))))
    assertNull(AppleAutoMatchPolicy.choose(target("C#"), listOf(app("C"))))
    assertNull(AppleAutoMatchPolicy.choose(target("A&B"), listOf(app("AB"))))
    assertNull(AppleAutoMatchPolicy.choose(target("100%"), listOf(app("100"))))
  }

  @Test fun distinctProductsWithExactTitlesNeedReviewRegardlessOfOrder() {
    val first = app("Notes", id = "111111111", bundle = "first.notes")
    val second = app(" NOTES ", id = "222222222", bundle = "second.notes")
    for (candidates in listOf(listOf(first, second), listOf(second, first))) {
      assertNull(AppleAutoMatchPolicy.choose(target("Notes"), candidates))
    }
  }

  @Test fun repeatedResultRowsDeduplicateOnlyWithinTheSameProductAndStorefront() {
    val first = app("Notes")
    assertSame(first, AppleAutoMatchPolicy.choose(target("Notes"),
      listOf(first, first.copy(publisher = "Localized publisher"))))
    assertNull(AppleAutoMatchPolicy.choose(target("Notes"), listOf(first, first.copy(country = "us"))))
    assertNull(AppleAutoMatchPolicy.choose(target("Notes"), listOf(first, first.copy(country = null))))
  }

  @Test fun uniqueExactBundleTakesPrecedenceOverTitlesAndSupportsLocalizedRename() {
    val strong = app("Localized product", bundle = "android.example.app")
    val weak = app("Reader", id = "222222222")
    assertSame(strong, AppleAutoMatchPolicy.choose(target("Reader"), listOf(weak, strong)))
    assertSame(strong, AppleAutoMatchPolicy.choose(target(""), listOf(strong)))
    assertSame(strong, AppleAutoMatchPolicy.choose(target("Reader"), listOf(strong, strong.copy())))
  }

  @Test fun ambiguousBundleDoesNotFallThroughToAUniqueTitle() {
    val first = app("Reader", bundle = "android.example.app")
    val second = app("Different localized name", id = "222222222", bundle = "android.example.app")
    assertNull(AppleAutoMatchPolicy.choose(target("Reader"), listOf(first, second)))
    assertNull(AppleAutoMatchPolicy.choose(target("Reader"), listOf(first, first.copy(country = "us"))))
  }

  @Test fun bundleComparisonsNeverUsePrefixesPunctuationFoldingOrEmptyValues() {
    for (bundle in listOf("android.example.app.pro", "Android.Example.App",
      "androidexampleapp", " android.example.app", "")) {
      assertNull(bundle, AppleAutoMatchPolicy.choose(target("Reader"), listOf(app("Other", bundle = bundle))))
    }
    assertNull(AppleAutoMatchPolicy.choose(target("", pkg = ""), listOf(app("", bundle = ""))))
  }

  @Test fun emptyOrPunctuationOnlyNamesDoNotMatch() {
    for (label in listOf("", " ", "…", "---", "!!!", "【】", "🔥")) {
      assertNull(label, AppleAutoMatchPolicy.choose(target(label), listOf(app(label))))
    }
    assertNull(AppleAutoMatchPolicy.choose(target("Reader"), emptyList()))
  }

  @Test fun queryNormalizesWidthAndWhitespaceWithoutDiscardingBrandOrEdition() {
    assertEquals("Foo Pro", AppleAutoMatchPolicy.query(" \tＦｏｏ　Pro\n "))
    assertEquals("天气预报", AppleAutoMatchPolicy.query("　天气预报　"))
    assertEquals("C++", AppleAutoMatchPolicy.query(" C++ "))
    assertEquals("Reader (Lite)", AppleAutoMatchPolicy.query("Reader (Lite)"))
    assertEquals("Foo Bar", AppleAutoMatchPolicy.query("Foo\u0000\u202eBar"))
  }

  @Test fun queryRejectsPunctuationOrSymbolOnlyTextIncludingAfterTruncation() {
    for (label in listOf("", " ", "---", "…", "【】", "🔥⚡", "###", "\u0000\u202e")) {
      assertEquals(label, "", AppleAutoMatchPolicy.query(label))
    }
    assertEquals("", AppleAutoMatchPolicy.query("!".repeat(100) + "Reader"))
  }

  @Test fun queryIsBoundedWithoutSplittingSupplementaryCharacters() {
    assertEquals("a".repeat(100), AppleAutoMatchPolicy.query("a".repeat(200)))
    val supplementary = "\ud840\udc00"
    assertEquals(supplementary.repeat(50), AppleAutoMatchPolicy.query(supplementary.repeat(60)))
    assertEquals("a".repeat(99), AppleAutoMatchPolicy.query("a".repeat(99) + supplementary))
    val query = AppleAutoMatchPolicy.query("a".repeat(99) + " b")
    assertTrue(query.length <= 100)
    assertEquals(query.trim(), query)
  }

  @Test fun truncatedQueryDoesNotAuthorizeAPrefixNameMatch() {
    val label = "a".repeat(100) + " Pro"
    val candidate = app(AppleAutoMatchPolicy.query(label))
    assertNull(AppleAutoMatchPolicy.choose(target(label), listOf(candidate)))
  }

  @Test fun reviewRetainsAmbiguousExactTitlesBeyondFirstFiveSearchResults() {
    val noise = List(6) { app("Reader alternatives $it", id = (600000 + it).toString()) }
    val first = app("Reader", id = "700001")
    val second = app(" READER ", id = "700002")
    val candidates = noise + listOf(first, second)
    assertNull(AppleAutoMatchPolicy.choose(target("Reader"), candidates))
    assertEquals(listOf(first, second) + noise.take(3),
      AppleAutoMatchPolicy.reviewCandidates(target("Reader"), candidates))
  }

  @Test fun reviewPrioritizesExactNamesOrBundlesAndDeduplicatesAfterRanking() {
    val exact = app("Ｒｅａｄｅｒ", id = "700001")
    val duplicate = exact.copy(name = "Different localized title")
    val noise = app("Other", id = "700002")
    val bundle = app("Renamed product", id = "700003", bundle = "android.example.app")
    assertEquals(listOf(bundle, exact, noise), AppleAutoMatchPolicy.reviewCandidates(
      target("Reader"), listOf(duplicate, noise, bundle, exact, bundle.copy())))
  }

  @Test fun reviewWithoutExactMatchesPreservesRankAndStorefrontIdentity() {
    val candidates = List(7) { app("Other $it", id = (600000 + it).toString()) }
    assertEquals(candidates.take(5),
      AppleAutoMatchPolicy.reviewCandidates(target("Reader"), candidates))
    val first = candidates.first()
    val regional = first.copy(country = "us")
    assertEquals(listOf(first, regional) + candidates.drop(1).take(3),
      AppleAutoMatchPolicy.reviewCandidates(target("Reader"),
        listOf(first, first.copy(), regional) + candidates.drop(1)))
  }

  @Test fun reviewDoesNotPrioritizeEmptyNormalizedNamesOrEmptyBundles() {
    val candidates = List(5) { app("Other $it", id = (600000 + it).toString()) }
    val blank = app("!!!", id = "700001", bundle = "")
    assertEquals(candidates, AppleAutoMatchPolicy.reviewCandidates(
      target(" ", pkg = ""), candidates + blank))
    assertTrue(AppleAutoMatchPolicy.reviewCandidates(target("Reader"), emptyList()).isEmpty())
  }

  @Test fun batchAndHistoryBoundsAreExplicit() {
    assertEquals(10, AppleAutoMatchPolicy.BATCH_SIZE)
    assertEquals(604_800_000L, AppleAutoMatchPolicy.HISTORY_TTL_MS)
  }
}
