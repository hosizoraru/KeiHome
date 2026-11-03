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
  @Test fun nameMatchingToggleNeedsRegenerationOnlyForConvertedPacks() {
    val covered = source.copy(nativeNames = setOf(target.packageName),
      systemFallback = SystemIconFallbackManifest(SystemIconFallback.DEFAULT, emptySet()))
    assertTrue(iconPackNeedsUpdate(pack, covered, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), nameMatching = true))
    val nameMatched = covered.copy(systemFallback = covered.systemFallback!!.copy(nameMatching = true))
    assertTrue(iconPackNeedsUpdate(pack, nameMatched, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), nameMatching = false))
    assertFalse(iconPackNeedsUpdate(pack.copy(format = IconPackFormat.MIUI_ICONS), covered,
      listOf(target), SystemIconFallback.DEFAULT, emptySet(), nameMatching = true))
  }
  @Test fun changingExternalColorOsFallbackRequiresAnUpdatedAppliedCopy() {
    val current = source.copy(nativeNames = setOf(target.packageName), systemFallback =
      SystemIconFallbackManifest(SystemIconFallback.DEFAULT, emptySet()))
    assertTrue(iconPackNeedsUpdate(pack, current, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), colorOsForOtherPacks = true))
    val enabled = current.copy(systemFallback = current.systemFallback!!.copy(colorOsFallback = true))
    assertFalse(iconPackNeedsUpdate(pack, enabled, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), colorOsForOtherPacks = true))
    assertTrue(iconPackNeedsUpdate(pack, enabled, listOf(target), SystemIconFallback.DEFAULT, emptySet()))
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

  @Test fun alreadyConsideredUnrenderableTargetsDoNotCauseAnEndlessUpdateNotice() {
    val applied = source.copy(generation = IconGenerationManifest(considered = setOf(target.flattened)))
    assertFalse(needed(applied))
    val newTarget = IconPackComponent("os.test.new", "os.test.new.Main")
    val updatedPack = pack.copy(entries = pack.entries + entry.copy(key = "new", component = newTarget))
    assertTrue(iconPackNeedsUpdate(updatedPack, applied, listOf(newTarget), SystemIconFallback.DEFAULT, emptySet()))
  }

  @Test fun officialAdaptationChangesReopenTheAppliedGenerationWithoutReactingToUnchangedPixels() {
    val applied = source.copy(nativeNames = setOf(target.packageName), generation = IconGenerationManifest(
      nativeAdaptations = mapOf(target.flattened to "none")))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT, emptySet(),
      nativeAdaptations = mapOf(target.flattened to "none")))
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT, emptySet(),
      nativeAdaptations = mapOf(target.flattened to "a".repeat(64))))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT, emptySet(),
      nativeAdaptations = mapOf("os.other.app" to "a".repeat(64))))
  }

  @Test fun ordinaryColorOsCanUseItsActualFallbackPaletteWithoutEnablingInspiration() {
    assertTrue(colorOsPaletteLocked(true, null))
    assertTrue(colorOsPaletteLocked(true, emptySet()))
    assertFalse(colorOsPaletteLocked(true, setOf("com.apple.android.music")))
    assertFalse(colorOsPaletteLocked(false, emptySet()))
  }
}
