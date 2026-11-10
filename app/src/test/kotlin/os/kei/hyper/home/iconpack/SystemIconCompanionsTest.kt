package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class SystemIconCompanionsTest {
  private val ui = "com.android.providers.downloads.ui"
  private val host = "com.android.providers.downloads"

  @Test fun dataInstalledDownloadUiRequiresBothSystemServiceAndMatchingSigner() {
    assertTrue(SystemIconCompanions.matches(ui, { it == host }, { a, b -> a == ui && b == host }))
    assertFalse(SystemIconCompanions.matches(ui, { true }, { _, _ -> false }))
    assertFalse(SystemIconCompanions.matches(ui, { false }, { _, _ -> error("No trusted host") }))
  }

  @Test fun packagePrefixesAndSimilarUiNamesNeverAdmitUnrelatedApplications() {
    for (name in listOf("com.android.unrelated", "$ui.fake", "com.example.downloads.ui")) {
      assertFalse(SystemIconCompanions.matches(name, { error("No host for $name") },
        { _, _ -> error("No signature query for $name") }))
    }
  }
}
