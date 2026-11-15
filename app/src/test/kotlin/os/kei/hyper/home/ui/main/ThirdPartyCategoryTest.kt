package os.kei.hyper.home.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyCategoryTest {
  @Test fun categoriesPartitionTheCatalogWithoutEmptyOrOversizedGroups() {
    val groups = thirdPartyComponents.groupBy { it.category }
    assertEquals(NoticeCategory.entries.toSet(), groups.keys)
    assertTrue(groups.values.all { it.size in 2..10 })
    val keys = groups.values.flatten().map { it.key }
    assertEquals(thirdPartyComponents.size, keys.size)
    assertEquals(keys.size, keys.toSet().size)
  }

  @Test fun categoryRoutesAreDistinctFromComponentAndLicenseRoutes() {
    assertEquals(NoticeCategory.entries.size, NoticeCategory.entries.distinctBy { it.key }.size)
    for (category in NoticeCategory.entries) {
      assertEquals(category, noticeCategoryForRoute("notices/category/${category.key}"))
    }
    for (route in listOf("notices", "notices/category/unknown", "notices/category/system/extra",
      "component/dexkit", "license/gpl")) {
      assertNull(noticeCategoryForRoute(route))
    }
  }

  @Test fun groupingPreservesReferenceAndLicenseIdentities() {
    val dexkit = thirdPartyComponents.first { it.key == "dexkit" }
    assertEquals(NoticeCategory.SYSTEM, dexkit.category)
    assertEquals(listOf("dexkit-apache", "dexkit-lgpl", "gpl", "dexkit-source"), dexkit.licenseKeys)
    val history = thirdPartyComponents.filter { it.kind == AttributionKind.HISTORY }
    assertTrue(history.all { it.category == NoticeCategory.REFERENCES })
    assertEquals(listOf("lsplt"), history.first { it.key == "lsplt" }.licenseKeys)
    assertEquals(NoticeCategory.IMAGES, thirdPartyComponents.first { it.key == "perfect-icons" }.category)
  }
}
