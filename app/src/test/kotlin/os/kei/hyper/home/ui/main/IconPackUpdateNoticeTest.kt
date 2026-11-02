package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.*

class IconPackUpdateNoticeTest {
  private val target = IconPackComponent("os.test.app", "os.test.app.Main")
  private val entry = IconPackEntry("mapped", component = target)
  private val pack = IconPackDescriptor("apk:os.test.icons", "Icons", IconPackFormat.INSTALLED_APK,
    IconPackSource.Installed("os.test.icons", 1, 100), listOf(entry))
  private val source = IconBrowserSource("revision", pack,
    mapOf(target.packageName to entry), emptySet(), emptySet(), nativeNames = emptySet())

  private fun needed(current: IconBrowserSource = source, original: IconPackDescriptor = pack) =
    iconPackNeedsUpdate(original, current, listOf(target), SystemIconFallback.DEFAULT, emptySet())

  @Test fun stockPreviewFallbackDoesNotHideMissingMappedArtwork() { assertTrue(needed()) }
  @Test fun unavailableStaticArtworkDoesNotCreateAnUnresolvableNotice() {
    assertFalse(iconPackNeedsUpdate(pack, source, listOf(target), SystemIconFallback.DEFAULT, emptySet()) { false })
  }
  @Test fun coveredComponentClearsNotice() {
    assertFalse(needed(source.copy(nativeNames = setOf(target.className!!))))
  }
  @Test fun deliberatelyUncoveredUserAppIsNotAnUpdateTask() {
    assertFalse(needed(original = pack.copy(entries = emptyList())))
  }
  @Test fun unknownInventoryDoesNotInventMissingIcons() {
    assertFalse(needed(source.copy(nativeNames = null)))
  }
  @Test fun installedSourceVersionChangeIsComparedWithAppliedModule() {
    val stamp = IconBuildInventory.sourceIdentity(pack.copy(
      id = SystemIconFallback.DEFAULT.sourceId(pack.id)))
    val current = source.copy(nativeNames = setOf(target.packageName), buildSource = stamp)
    assertFalse(needed(current))
    assertTrue(needed(current, pack.copy(source = IconPackSource.Installed("os.test.icons", 2, 200))))
  }
  @Test fun fallbackPreferenceDifferenceNeedsRegeneration() {
    assertTrue(needed(source.copy(nativeNames = setOf(target.packageName),
      systemFallback = SystemIconFallbackManifest(SystemIconFallback.CLASSIC, setOf(target.packageName)))))
  }
  @Test fun inventoryIsCanonicalIncludingEmptyModules() {
    listOf(emptySet(), setOf("os.test.app", "os.test.other.Main")).forEach {
      assertEquals(it, IconBuildInventory.decodeNames(IconBuildInventory.encodeNames(it)))
    }
  }
  @Test fun forgedOrDuplicateInventoryNamesAreRejected() {
    listOf("os.test.app\nos.test.app\n", "../icon\n", "os.test.app").forEach {
      assertTrue(runCatching { IconBuildInventory.decodeNames(it.toByteArray()) }.isFailure)
    }
  }
}
