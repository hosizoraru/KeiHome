package os.kei.hyper.home.ui.main

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackDescriptor
import os.kei.hyper.home.iconpack.IconPackFormat
import os.kei.hyper.home.iconpack.IconPackSource
import os.kei.hyper.home.iconpack.StoredIconPack

class IconPackCatalogSortTest {
  @Test fun progressUpdatesNeverTraversePackMappingsAndReplacementSnapshotsRemainVisible() {
    val mappings = object : AbstractList<os.kei.hyper.home.iconpack.IconPackEntry>() {
      override val size: Int get() = 100_000
      override fun get(index: Int): os.kei.hyper.home.iconpack.IconPackEntry =
        error("UI state comparison must not inspect catalog mappings")
    }
    val descriptor = IconPackDescriptor("large", "Large", IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed("org.example.large", 1, 1), mappings)
    val first = IconPackCardData(descriptor, 10, emptyList())
    assertEquals(first, first.copy())
    assertEquals(first.hashCode(), first.copy().hashCode())
    org.junit.Assert.assertNotEquals(first, first.copy(matchedApps = 11))
    org.junit.Assert.assertNotEquals(first, first.copy(pack = descriptor.copy()))
    val old = IconPacksUiState(packs = listOf(first))
    org.junit.Assert.assertNotEquals(old, old.copy(preparing = true))
    org.junit.Assert.assertNotEquals(old,
      old.copy(packs = listOf(first.copy(pack = descriptor.copy()))))
  }

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

  @Test fun importedAndAppliedAreSeparateStagesWithoutOfferingASecondConversion() {
    val receipt = os.kei.hyper.home.status.NativeIconImportReport("token", 0, "source", "IMPORTED", "new")
    val ready = state.copy(nativeResult = receipt)
    assertEquals(receipt, pendingNativeApplication(ready, "old"))
    assertEquals(null, pendingNativeApplication(ready, "new"))
    assertEquals(null, pendingNativeApplication(ready, null))
    assertNull(pendingNativeApplication(ready.copy(nativeResult = receipt.copy(convertedReady = false)), "old"))
    assertEquals(receipt.copy(convertedReady = true),
      pendingNativeApplication(ready.copy(nativeResult = receipt.copy(convertedReady = true)), "old"))
    listOf("FAILED", "UNSUPPORTED", "UNCERTAIN", "IMPORTING").forEach {
      assertEquals(null, pendingNativeApplication(ready.copy(nativeResult = receipt.copy(status = it)), "old"))
    }
  }

  @Test fun changingFallbackDoesNotApplyAnOutdatedPendingConversion() {
    val hyper = os.kei.hyper.home.data.SystemIconFallback.perfect("HyperOS4")
    val other = os.kei.hyper.home.data.SystemIconFallback.perfect("HyperOS3")
    val receipt = os.kei.hyper.home.status.NativeIconImportReport("token", 0,
      hyper.sourceId("source"), "IMPORTED", "new")
    val ready = state.copy(nativeResult = receipt)
    assertEquals(receipt, pendingNativeApplication(ready, "old", hyper))
    assertNull(pendingNativeApplication(ready, "old", other))
    assertNull(pendingNativeApplication(ready, "old", os.kei.hyper.home.data.SystemIconFallback.DEFAULT))
    val legacy = ready.copy(nativeResult = receipt.copy(sourceId = "source"))
    assertNotNull(pendingNativeApplication(legacy, "old", os.kei.hyper.home.data.SystemIconFallback.DEFAULT))
    assertNull(pendingNativeApplication(legacy, "old", hyper))
  }
}
