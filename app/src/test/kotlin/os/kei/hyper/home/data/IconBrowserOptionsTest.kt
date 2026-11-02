package os.kei.hyper.home.data

import org.junit.Assert.*
import org.junit.Test

class IconBrowserOptionsTest {
  @Test fun savesEveryUserChoiceIncludingDirectionAndOverlayVisibility() {
    val value = IconBrowserOptions(2, 1, 2, 4, 3, true, true, true)
    assertEquals(value, IconBrowserOptions.restore(value.stored()))
  }
  @Test fun olderAndInvalidStorageRestoresSafeDefaults() {
    assertEquals(IconBrowserOptions(), IconBrowserOptions.restore(emptyMap<String, Any>()))
    assertEquals(IconBrowserOptions(), IconBrowserOptions.restore(mapOf("scope" to 3,
      "origin" to -1, "coverage" to "2", "support" to 5, "sort" to 4, "overlays" to "true")))
  }
}
