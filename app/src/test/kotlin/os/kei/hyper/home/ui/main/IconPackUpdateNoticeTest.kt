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
  @Test fun coveredLegacyFallbackNeedsOneRegenerationAfterNativeFallbackFixes() {
    for (version in listOf(1, 2)) {
      val legacy = IconBuildInventory.sourceIdentity(pack.copy(
        id = "${pack.id}:system-fallback-v$version:DEFAULT"))
      val applied = source.copy(nativeNames = setOf(target.packageName), buildSource = legacy)
      assertTrue(needed(applied))
      val regenerated = applied.copy(buildSource = IconBuildInventory.sourceIdentity(pack.copy(
        id = SystemIconFallback.DEFAULT.sourceId(pack.id))))
      assertFalse(needed(regenerated))
    }
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

  @Test fun legacyGenerationNeedsOneUpdateThenClearsForEitherSelectedScope() {
    val legacy = IconGenerationManifest.decode(
      "KeiHomeIconGeneration1\ntarget\t${target.flattened}\n".toByteArray(Charsets.UTF_8))
    val applied = source.copy(generation = legacy)
    for (installedOnly in listOf(false, true)) {
      assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), installedOnly = installedOnly))
      val updated = applied.copy(generation = IconGenerationManifest.decode(
        legacy.copy(schemaVersion = 2, installedOnly = installedOnly).encode()))
      assertFalse(iconPackNeedsUpdate(pack, updated, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), installedOnly = installedOnly))
    }
  }

  @Test fun changingInstalledOnlyInEitherDirectionNeedsOneUpdateThenClears() {
    for (previous in listOf(false, true)) {
      val manifest = IconGenerationManifest(considered = setOf(target.flattened), installedOnly = previous)
      val applied = source.copy(generation = manifest)
      assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), installedOnly = previous))
      assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), installedOnly = !previous))
      val updated = applied.copy(generation = IconGenerationManifest.decode(
        manifest.copy(installedOnly = !previous).encode()))
      assertFalse(iconPackNeedsUpdate(pack, updated, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), installedOnly = !previous))
    }
  }

  @Test fun inventoryWithoutGenerationKeepsDefaultScopeButUpdatesWhenInstalledOnlyIsEnabled() {
    val applied = source.copy(nativeNames = setOf(target.packageName))
    assertFalse(needed(applied))
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), installedOnly = true))
    val updated = applied.copy(generation = IconGenerationManifest(installedOnly = true))
    assertFalse(iconPackNeedsUpdate(pack, updated, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), installedOnly = true))
  }

  @Test fun installedOnlyStillDetectsANewlyInstalledMappedTargetAndClearsAfterGeneration() {
    val newTarget = IconPackComponent("os.test.new", "os.test.new.Main")
    val original = pack.copy(entries = pack.entries + entry.copy(key = "new", component = newTarget))
    val manifest = IconGenerationManifest(considered = setOf(target.flattened), installedOnly = true)
    val applied = source.copy(generation = manifest)
    assertFalse(iconPackNeedsUpdate(original, applied, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), installedOnly = true))
    val installedTargets = listOf(target, newTarget)
    assertTrue(iconPackNeedsUpdate(original, applied, installedTargets, SystemIconFallback.DEFAULT,
      emptySet(), installedOnly = true))
    val updated = applied.copy(generation = IconGenerationManifest.decode(
      manifest.copy(considered = manifest.considered + newTarget.flattened).encode()))
    assertFalse(iconPackNeedsUpdate(original, updated, installedTargets, SystemIconFallback.DEFAULT,
      emptySet(), installedOnly = true))
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

  @Test fun perfectStyleChangesRequireRegeneration() {
    val selected = SystemIconFallback.perfect("HyperOS4")
    val applied = source.copy(nativeNames = setOf(target.packageName),
      systemFallback = SystemIconFallbackManifest(selected, emptySet()))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet()))
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target),
      SystemIconFallback.perfect("Material You"), emptySet()))
    assertTrue(needed(applied))
    assertTrue(iconPackNeedsUpdate(pack, source.copy(nativeNames = setOf(target.packageName),
      systemFallback = SystemIconFallbackManifest(SystemIconFallback.DEFAULT, emptySet())),
      listOf(target), selected, emptySet()))
  }

  @Test fun perfectArchiveChangeRequiresUpdateEvenForCoveredOrUnknownInventory() {
    val selected = SystemIconFallback.perfect("HyperOS4")
    val previous = "a".repeat(64)
    val current = "b".repeat(64)
    for (names in listOf(setOf(target.packageName), null)) {
      val applied = source.copy(nativeNames = names, systemFallback =
        SystemIconFallbackManifest(selected, emptySet(), perfectSource = previous))
      assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet(),
        perfectSource = previous))
      assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet(),
        perfectSource = current))
      val updated = applied.copy(systemFallback = applied.systemFallback!!.copy(perfectSource = current))
      assertFalse(iconPackNeedsUpdate(pack, updated, listOf(target), selected, emptySet(),
        perfectSource = current))
      assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet()))
    }
  }

  @Test fun localPerfectSourceAddsProvenanceToLegacyAppliedFallbacks() {
    val selected = SystemIconFallback.perfect("HyperOS4")
    for (manifest in listOf(null, SystemIconFallbackManifest(selected, emptySet()))) {
      val applied = source.copy(nativeNames = setOf(target.packageName), systemFallback = manifest)
      assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet()))
      assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet(),
        perfectSource = "a".repeat(64)))
    }
  }

  @Test fun nativeFallbacksIgnorePerfectSourceChanges() {
    for (fallback in SystemIconFallback.entries) {
      val applied = source.copy(nativeNames = setOf(target.packageName),
        systemFallback = SystemIconFallbackManifest(fallback, emptySet()))
      assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), fallback, emptySet(),
        perfectSource = "b".repeat(64)))
    }
  }

  @Test fun perfectSystemTargetsRespectCompletedInventoryWithoutRepeatedUpdates() {
    val selected = SystemIconFallback.perfect("HyperOS4")
    val unmapped = pack.copy(entries = emptyList())
    for (considered in listOf(emptySet(), setOf(target.flattened))) {
      val applied = source.copy(entries = emptyMap(),
        systemFallback = SystemIconFallbackManifest(selected, emptySet()),
        generation = IconGenerationManifest(considered = considered))
      var attempts = 0
      assertEquals(considered.isEmpty(), iconPackNeedsUpdate(unmapped, applied, listOf(target), selected,
        setOf(target.packageName)) { attempts++; true })
      assertEquals(if (considered.isEmpty()) 1 else 0, attempts)
      assertFalse(iconPackNeedsUpdate(unmapped, applied, listOf(target), selected,
        setOf(target.packageName)) { false })
      assertFalse(iconPackNeedsUpdate(unmapped, applied, listOf(target), selected, emptySet()))
      val updated = applied.copy(nativeNames = setOf(target.packageName))
      assertFalse(iconPackNeedsUpdate(unmapped, updated, listOf(target), selected,
        setOf(target.packageName)) { fail("Covered Perfect target must not be retried"); true })
    }
  }

  @Test fun perfectConsideredUserTargetsRetainTheirExistingUpdateBehavior() {
    val selected = SystemIconFallback.perfect("HyperOS4")
    val applied = source.copy(generation = IconGenerationManifest(considered = setOf(target.flattened)),
      systemFallback = SystemIconFallbackManifest(selected, emptySet()))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), selected, emptySet()) {
      fail("Previously considered user target must not be retried"); true
    })
    assertTrue(iconPackNeedsUpdate(pack, applied.copy(generation = null),
      listOf(target), selected, emptySet()))
  }

  @Test fun ordinaryColorOsCanUseItsActualFallbackPaletteWithoutEnablingInspiration() {
    assertTrue(colorOsPaletteLocked(true, null))
    assertTrue(colorOsPaletteLocked(true, emptySet()))
    assertFalse(colorOsPaletteLocked(true, setOf("com.apple.android.music")))
    assertFalse(colorOsPaletteLocked(false, emptySet()))
  }
}
