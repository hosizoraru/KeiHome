package os.kei.hyper.home.ui.main

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.apple.AppleAppMatch
import os.kei.hyper.home.iconpack.apple.AppleAppTarget
import os.kei.hyper.home.iconpack.apple.AppleAutoMatchPolicy
import os.kei.hyper.home.iconpack.apple.AppleMatchAttempt
import os.kei.hyper.home.iconpack.apple.AppleMatchAttemptStatus
import os.kei.hyper.home.ui.apple.AppleDarkCapability
import os.kei.hyper.home.ui.apple.AppleMappingFilter
import os.kei.hyper.home.ui.apple.AppleMappingRow
import os.kei.hyper.home.ui.apple.AppleMappingSort
import os.kei.hyper.home.ui.apple.appleMappingRows
import os.kei.hyper.home.ui.apple.appleMappingViewRows

class AppleMappingBrowserTest {
  @Test fun viewsPartitionTheSameSortedAndFilteredRows() {
    val filtered = appleMappingRows(rows, "", AppleMappingFilter.ALL, AppleMappingSort.NAME, "us", "", false, Locale.US)
    val groups = appleMappingViewRows(filtered).map { group -> group.map { it.target.packageName } }
    assertEquals(listOf("app.a", "app.b", "app.c", "app.z"), groups[0])
    assertEquals(listOf("app.a", "app.z"), groups[1])
    assertEquals(listOf("app.b", "app.c"), groups[2])
    val custom = appleMappingRows(rows, "", AppleMappingFilter.CUSTOM, AppleMappingSort.NAME, "us", "", false, Locale.US)
    assertEquals(listOf(2, 1, 1), appleMappingViewRows(custom).map { it.size })
  }
  private val rows = listOf(
    AppleMappingRow(AppleAppTarget("app.z", "Zoo", false),
      AppleAppMatch("1108187390", "Apple Music", "Apple", "com.apple.Music", "jp"), true),
    AppleMappingRow(AppleAppTarget("app.a", "Alpha", true),
      AppleAppMatch("1598433714", "Classical", "Apple", "com.apple.music.classical"), false),
    AppleMappingRow(AppleAppTarget("app.b", "Beta", false), null, true),
    AppleMappingRow(AppleAppTarget("app.c", "Charlie", true), null, false),
  )

  private fun browse(filter: AppleMappingFilter = AppleMappingFilter.ALL, query: String = "",
    sort: AppleMappingSort = AppleMappingSort.NAME, region: String = "", gallery: Boolean = false) =
    appleMappingRows(rows, query, filter, sort, "us", region, gallery, Locale.US).map { it.target.packageName }

  @Test fun galleryContainsOnlyMappedAppsAndCanIsolateManualChoices() {
    assertEquals(listOf("app.a", "app.z"), browse(gallery = true))
    assertEquals(listOf("app.z"), browse(AppleMappingFilter.CUSTOM, gallery = true))
    assertEquals(listOf("app.a"), browse(AppleMappingFilter.AUTOMATIC, gallery = true))
  }

  @Test fun galleryViewsPartitionTheSameSearchResultsByAppOrigin() {
    val matches = appleMappingRows(rows, "apple", AppleMappingFilter.ALL, AppleMappingSort.NAME_DESC,
      "us", "", true, Locale.US)
    val views = appleMappingViewRows(matches, matchedOnly = true)
      .map { view -> view.map { it.target.packageName } }
    assertEquals(listOf(listOf("app.z", "app.a"), listOf("app.a"), listOf("app.z")), views)

    val manual = appleMappingRows(rows, "apple", AppleMappingFilter.CUSTOM, AppleMappingSort.NAME_DESC,
      "us", "jp", true, Locale.US)
    assertEquals(listOf(1, 0, 1), appleMappingViewRows(manual, matchedOnly = true).map { it.size })
  }

  @Test fun searchFindsDestinationIdentityAndSourcePackageWithoutChangingAssignments() {
    assertEquals(listOf("app.a"), browse(query = "CLASSICAL"))
    assertEquals(listOf("app.z"), browse(query = "com.apple.Music", region = "jp"))
    assertEquals(listOf("app.b"), browse(query = "APP.B"))
    assertEquals(listOf("app.a", "app.z"), browse(query = "Apple"))
    assertEquals("1108187390", rows.first().app?.id)
  }

  @Test fun excludedUnmatchedAndSystemFiltersRemainDistinct() {
    assertEquals(listOf("app.b"), browse(AppleMappingFilter.EXCLUDED))
    assertEquals(listOf("app.b", "app.c"), browse(AppleMappingFilter.UNMAPPED))
    assertEquals(listOf("app.a", "app.c"), browse(AppleMappingFilter.SYSTEM))
    assertEquals(listOf("app.b", "app.z"), browse(AppleMappingFilter.USER))
  }

  @Test fun effectiveStorefrontUsesTheMappingOverrideThenGlobalDefault() {
    assertEquals(listOf("app.z"), browse(AppleMappingFilter.CROSS_REGION))
    assertEquals(listOf("app.a"), browse(region = "us"))
    assertEquals(listOf("app.z"), browse(region = "jp"))
    assertTrue(browse(region = "hk").isEmpty())
  }

  @Test fun sortingIsDeterministicAndLeavesUnmappedDestinationsAtTheEnd() {
    assertEquals(listOf("app.z", "app.c", "app.b", "app.a"), browse(sort = AppleMappingSort.NAME_DESC))
    assertEquals(listOf("app.a", "app.b", "app.c", "app.z"), browse(sort = AppleMappingSort.PACKAGE))
    assertEquals(listOf("app.z", "app.a", "app.b", "app.c"), browse(sort = AppleMappingSort.APPLE_NAME))
    assertEquals(listOf("app.b", "app.z", "app.a", "app.c"), browse(sort = AppleMappingSort.CUSTOM_FIRST))
  }

  @Test fun missingDarkIncludesOnlyResolvedMappingsWithoutAnIndependentDarkVariant() {
    val cached = rows.map {
      it.copy(darkCapability = if (it.target.packageName == "app.z") AppleDarkCapability.MISSING
        else AppleDarkCapability.INDEPENDENT)
    }
    assertEquals(listOf("app.z"), appleMappingRows(cached, "", AppleMappingFilter.MISSING_DARK,
      AppleMappingSort.NAME, "us").map { it.target.packageName })
    assertTrue(appleMappingRows(rows, "", AppleMappingFilter.MISSING_DARK, AppleMappingSort.NAME, "us").isEmpty())
    val unmapped = rows.map { it.copy(darkCapability = AppleDarkCapability.MISSING) }
    assertEquals(listOf("app.a", "app.z"), appleMappingRows(unmapped, "", AppleMappingFilter.MISSING_DARK,
      AppleMappingSort.NAME, "us").map { it.target.packageName })
  }

  @Test fun missingDarkComposesWithGalleryRegionSearchAndSort() {
    val cached = rows.map { it.copy(darkCapability = AppleDarkCapability.MISSING) }
    assertEquals(listOf("app.z"), appleMappingRows(cached, "apple", AppleMappingFilter.MISSING_DARK,
      AppleMappingSort.PACKAGE, "us", region = "jp", matchedOnly = true).map { it.target.packageName })
  }

  @Test fun sharedOriginalRemainsVisibleInTheNoIndependentDarkFilter() {
    val shared = rows.map { it.copy(darkCapability = AppleDarkCapability.SHARED) }
    assertEquals(listOf("app.a", "app.z"), appleMappingRows(shared, "", AppleMappingFilter.MISSING_DARK,
      AppleMappingSort.NAME, "us").map { it.target.packageName })
  }

  @Test fun reviewFilterExcludesResolvedMappingsAndManualExclusions() {
    val attempt = AppleMatchAttempt(AppleMatchAttemptStatus.REVIEW, System.currentTimeMillis(),
      System.currentTimeMillis() + 60_000, "cn", listOf(rows[0].app!!))
    val candidates = rows.map { it.copy(attempt = attempt) }
    val review = appleMappingRows(candidates, "", AppleMappingFilter.NEEDS_REVIEW,
      AppleMappingSort.NAME, "us")
    assertEquals(listOf("app.c"), review.map { it.target.packageName })
    assertEquals(review, appleMappingRows(candidates, "", AppleMappingFilter.NEEDS_REVIEW,
      AppleMappingSort.NAME, "us", region = "cn"))
    assertTrue(appleMappingRows(candidates, "", AppleMappingFilter.NEEDS_REVIEW,
      AppleMappingSort.NAME, "us", matchedOnly = true).isEmpty())
  }

  @Test fun recentFilterUsesSevenDayWindowAndRecordedStorefrontForUnmappedRows() {
    val time = System.currentTimeMillis()
    val recent = rows[3].copy(attempt = AppleMatchAttempt(AppleMatchAttemptStatus.NO_MATCH,
      time - 1_000, time + 60_000, "cn"))
    val old = rows[1].copy(attempt = AppleMatchAttempt(AppleMatchAttemptStatus.NO_MATCH,
      time - AppleAutoMatchPolicy.HISTORY_TTL_MS - 60_000, time - 60_000, "us"))
    val all = listOf(recent, old, rows[0])
    assertEquals(listOf(recent), appleMappingRows(all, "", AppleMappingFilter.RECENTLY_TRIED,
      AppleMappingSort.NAME, "us"))
    assertEquals(listOf(recent), appleMappingRows(all, "", AppleMappingFilter.RECENTLY_TRIED,
      AppleMappingSort.NAME, "us", region = "cn"))
    assertTrue(appleMappingRows(all, "", AppleMappingFilter.RECENTLY_TRIED,
      AppleMappingSort.NAME, "us", region = "us").isEmpty())
  }
}
