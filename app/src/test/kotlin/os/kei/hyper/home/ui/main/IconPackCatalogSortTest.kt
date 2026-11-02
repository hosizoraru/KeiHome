package os.kei.hyper.home.ui.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.StoredIconPack

class IconPackCatalogSortTest {
  private fun installed(id: String, label: String, updated: Long, matches: Int) = IconPackCardData(
    IconPackDescriptor(id, label, IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed("org.example.$id", 1, updated), emptyList()), matches, emptyList())

  private val alpha = installed("alpha", "Alpha", 10, 1)
  private val beta = installed("beta", "Beta", 20, 4)
  private val zeta = installed("zeta", "Zeta", 30, 2)
  private val imported = IconPackCardData(IconPackDescriptor("file", "Local pack", IconPackFormat.APK,
    IconPackSource.Imported(StoredIconPack("file", "Special-icons.apk", 40, IconPackFormat.APK,
      File("unused"), null, 0, null)), emptyList()), 0, emptyList())
  private val state = IconPacksUiState(packs = listOf(beta, zeta, alpha, imported),
    nativeAssociations = mapOf("alpha:system-fallback-v1:CLASSIC" to "old"),
    nativeSources = mapOf("old" to "zeta:system-fallback-v1:DEFAULT"))

  @Test fun activeReceiptWinsAndRemainsFirstInReverseOrder() {
    val active = activeCatalogPackId("old", state)
    assertEquals("zeta", active)
    assertEquals(listOf("zeta", "alpha", "beta", "file"),
      catalogPackRows(state, "installed", active, "", 0, false).map { it.pack.id })
    assertEquals(listOf("zeta", "file", "beta", "alpha"),
      catalogPackRows(state, "installed", active, "", 0, true).map { it.pack.id })
  }

  @Test fun queryMatchesPackageAndImportedFilenameWithoutForcingActiveResult() {
    assertEquals(listOf("beta"), catalogPackRows(state, "installed", "zeta", "EXAMPLE.BETA", 0, false).map { it.pack.id })
    assertEquals(listOf("file"), catalogPackRows(state, "installed", "zeta", "special-icons", 0, false).map { it.pack.id })
  }

  @Test fun recentAndCoverageOrdersUseSourceMetadata() {
    assertEquals(listOf("file", "zeta", "beta", "alpha"),
      catalogPackRows(state, "installed", null, "", 2, false).map { it.pack.id })
    assertEquals(listOf("beta", "zeta", "alpha", "file"),
      catalogPackRows(state, "installed", null, "", 3, false).map { it.pack.id })
    assertEquals("alpha", activeCatalogPackId("old", state.copy(nativeSources = emptyMap())))
  }
}
