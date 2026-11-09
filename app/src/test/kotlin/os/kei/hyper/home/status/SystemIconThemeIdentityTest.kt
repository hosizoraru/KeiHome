package os.kei.hyper.home.status

import org.junit.Assert.*
import org.junit.Test

class SystemIconThemeIdentityTest {
  private val inventory = SystemIconThemeReport(SystemIconThemeReport.Source.THEME,
    "Original theme", "/data/system/theme/icons", 0, 0, false, "revision", version = "15.0.231111")

  @Test fun unavailableProviderPreservesActualPartialModuleLabels() {
    val result = inventory.withIdentity(null, null, null, null)
    assertEquals(inventory, result)
    assertFalse(result.complete)
  }

  @Test fun providerIdentityAddsAssociationWithoutDiscardingSourceVersion() {
    val result = inventory.withIdentity("Localized title", "local-id", false, "digest")
    assertEquals("Localized title", result.title)
    assertEquals(inventory.version, result.version)
    assertEquals("local-id", result.localId)
    assertEquals("digest", result.nativeHash)
  }

  @Test fun providerDefaultMustNotReuseLabelsFromAThemeModule() {
    val result = inventory.withIdentity(null, "default", true, null)
    assertEquals(SystemIconThemeReport.Source.DEFAULT, result.source)
    assertNull(result.title)
    assertNull(result.version)
  }
}
