package os.kei.hyper.home.ui.main

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import os.kei.hyper.home.iconpack.apple.AppleAppMatch
import os.kei.hyper.home.iconpack.apple.AppleAppTarget

class AppleMappingBrowserTest {
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
}
