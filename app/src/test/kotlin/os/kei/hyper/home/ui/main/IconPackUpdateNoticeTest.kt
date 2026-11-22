package os.kei.hyper.home.ui.main

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.*

class IconPackUpdateNoticeTest {
  @Test fun extraDarkChoiceRequiresApplyInBothDirections() {
    val chosen = setOf(ExtraIcon.SCENE.packageName)
    val applied = source.copy(generation = IconGenerationManifest(
      schemaVersion = IconGenerationManifest.CURRENT_SCHEMA,
      considered = setOf(target.flattened), extraIconPackages = chosen))
    assertTrue(iconPackNeedsUpdate(pack, source, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), extraIconPackages = chosen))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), extraIconPackages = chosen))
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT, emptySet()))
  }

  @Test fun fullBleedPaddingRequiresRegenerationIndependentlyOfGenericColoring() {
    fun applied(generic: Boolean, inset: Boolean) = source.copy(generation =
      IconGenerationManifest(schemaVersion = 15, considered = setOf(target.flattened),
        monetUnadaptedIcons = generic, monetFullBleedIconPadding = inset))
    assertTrue(iconPackNeedsUpdate(pack, applied(true, false), listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = true, monetFullBleedIconPadding = true))
    assertTrue(iconPackNeedsUpdate(pack, applied(true, true), listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = true, monetFullBleedIconPadding = false))
    assertFalse(iconPackNeedsUpdate(pack, applied(true, true), listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = true, monetFullBleedIconPadding = true))
    assertTrue(iconPackNeedsUpdate(pack, applied(false, false), listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = false, monetFullBleedIconPadding = true))
    assertTrue(iconPackNeedsUpdate(pack, applied(false, true), listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = false, monetFullBleedIconPadding = false))
  }

  @Test fun colorOsMonochromeGeometryUpgradeRequiresReapplyWithoutGenericColoring() {
    for (schema in listOf(12, 13, 14)) {
      val current = source.copy(generation = IconGenerationManifest(schemaVersion = schema,
        colorOsPackages = setOf(target.packageName), considered = setOf(target.flattened)))
      assertEquals(schema < 14, iconPackNeedsUpdate(pack, current, listOf(target),
        SystemIconFallback.DEFAULT, emptySet(), monetUnadaptedIcons = false))
    }
    assertFalse(iconPackNeedsUpdate(pack, source.copy(generation = IconGenerationManifest(
      schemaVersion = 12, considered = setOf(target.flattened))), listOf(target),
      SystemIconFallback.DEFAULT, emptySet(), monetUnadaptedIcons = false))
  }

  @Test fun genericMonetLayerGeometryUpgradeRequiresReapplyOnce() {
    for (schema in listOf(11, 12)) {
      val current = source.copy(generation =
        IconGenerationManifest(schemaVersion = schema, monetUnadaptedIcons = true,
          considered = setOf(target.flattened)))
      assertEquals("Only old generic Monet output needs regeneration", schema < 12,
        iconPackNeedsUpdate(pack, current, listOf(target), SystemIconFallback.DEFAULT,
          emptySet(), monetUnadaptedIcons = true))
    }
  }

  @Test fun nativeLayerCoverageUpgradeRefreshesOnlyOlderNativePacksWithColoringEnabled() {
    for (format in listOf(IconPackFormat.MIUI_MTZ, IconPackFormat.MIUI_ICONS, IconPackFormat.INSTALLED_APK)) {
      for (schema in listOf(15, IconGenerationManifest.CURRENT_SCHEMA)) {
        for (enabled in listOf(false, true)) {
          val current = source.copy(generation = IconGenerationManifest(schemaVersion = schema,
            monetUnadaptedIcons = enabled, considered = setOf(target.flattened)))
          assertEquals(enabled && schema == 15 && format != IconPackFormat.INSTALLED_APK,
            iconPackNeedsUpdate(pack.copy(format = format), current, listOf(target),
              SystemIconFallback.DEFAULT, emptySet(), monetUnadaptedIcons = enabled))
        }
      }
    }
  }

  @Test fun genericMonetSettingChangesRequireRegenerationInBothDirections() {
    val enabled = source.copy(generation =
      IconGenerationManifest(schemaVersion = 11, monetUnadaptedIcons = true))
    assertTrue(iconPackNeedsUpdate(pack, source, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = true))
    assertTrue(iconPackNeedsUpdate(pack, enabled, listOf(target), SystemIconFallback.DEFAULT,
      emptySet(), monetUnadaptedIcons = false))
  }
  private val target = IconPackComponent("os.test.app", "os.test.app.Main")
  private val entry = IconPackEntry("mapped", component = target)
  private val pack = IconPackDescriptor("apk:os.test.icons", "Icons", IconPackFormat.INSTALLED_APK,
    IconPackSource.Installed("os.test.icons", 1, 100), listOf(entry))
  private val source = IconBrowserSource("revision", pack,
    mapOf(target.packageName to entry), emptySet(), emptySet(), nativeNames = emptySet())

  private fun needed(current: IconBrowserSource = source, original: IconPackDescriptor = pack,
    mappings: RecommendedIconMappings = RecommendedIconMappings()) =
    iconPackNeedsUpdate(original, current, listOf(target), SystemIconFallback.DEFAULT, emptySet(),
      recommendedMappings = mappings)

  @Test fun customChangesInEitherDirectionRequireReapplyAndOverrideStalePreservation() {
    val identity = "a".repeat(64)
    val generation = IconGenerationManifest(schemaVersion = 10, customIconIdentity = identity)
    val applied = source.copy(generation = generation)
    fun changed(current: IconBrowserSource, saved: String?) =
      iconPackNeedsUpdate(pack, current, listOf(target), SystemIconFallback.DEFAULT, emptySet(),
        preservedPackages = setOf(target.packageName), customizedPackages = setOf(target.packageName),
        customIconIdentity = saved)
    assertFalse(changed(applied, identity))
    assertTrue(changed(source, identity))
    assertTrue(changed(applied, "b".repeat(64)))
    assertTrue(changed(applied, null))
  }

  @Test fun mappingChangesInEitherDirectionNeedRegenerationAndMatchingSettingsStayClean() {
    val preserved = setOf("os.test.preserved")
    val adaptations = mapOf(target.flattened to "a".repeat(64))
    val base = IconGenerationManifest(
      considered = setOf(target.flattened), nativeAdaptations = adaptations,
      installedOnly = true, completeSystemIconConfig = true,
      activeLauncherTargets = setOf(target.flattened), preservedIconPackages = preserved)
    fun needsUpdate(applied: IconBrowserSource, selected: RecommendedIconMappings) =
      iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT, emptySet(),
        nameMatching = true, colorOsForOtherPacks = true, nativeAdaptations = adaptations,
        installedOnly = true, completeSystemIconConfig = true, preservedPackages = preserved,
        recommendedMappings = selected) {
        fail("Fully covered targets must not retry completion when comparing mapping metadata")
        false
      }
    for (savedBits in 0..7) {
      val saved = RecommendedIconMappings.fromBits(savedBits)
      val applied = source.copy(nativeNames = setOf(target.packageName),
        systemFallback = SystemIconFallbackManifest(SystemIconFallback.DEFAULT, emptySet(),
          nameMatching = true, colorOsFallback = true),
        generation = IconGenerationManifest.decode(base.copy(recommendedMappings = saved).encode()))
      assertFalse(needsUpdate(applied, saved))
      for (selectedBits in 0..7) {
        val selected = RecommendedIconMappings.fromBits(selectedBits)
        assertEquals("saved=$savedBits selected=$selectedBits",
          saved != selected, needsUpdate(applied, selected))
        val regenerated = applied.copy(generation = IconGenerationManifest.decode(
          base.copy(recommendedMappings = selected).encode()))
        assertFalse("saved=$savedBits selected=$selectedBits", needsUpdate(regenerated, selected))
      }
    }
  }

  @Test fun legacyMetadataRetainsOldChoicesAndRequiresUpdateForNewPhoneRecommendation() {
    val legacy = IconGenerationManifest.decode(
      "KeiHomeIconGeneration7\nscope\tall\nshape\toriginal\nmask\toriginal\n".toByteArray(Charsets.UTF_8))
    assertEquals(7, legacy.schemaVersion)
    for (generation in listOf(null, legacy)) {
      for (names in listOf(setOf(target.packageName), null)) {
        val applied = source.copy(nativeNames = names, generation = generation)
        assertFalse(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
          emptySet(), recommendedMappings =
            if (generation == null) RecommendedIconMappings() else RecommendedIconMappings.LEGACY))
        for (bits in (0..7).filter { it != (generation?.recommendedMappings ?: RecommendedIconMappings()).bits }) {
          val selected = RecommendedIconMappings.fromBits(bits)
          assertTrue(iconPackNeedsUpdate(pack, applied, listOf(target), SystemIconFallback.DEFAULT,
            emptySet(), recommendedMappings = selected))
          val regenerated = applied.copy(generation = IconGenerationManifest.decode(
            IconGenerationManifest(recommendedMappings = selected).encode()))
          assertFalse(iconPackNeedsUpdate(pack, regenerated, listOf(target), SystemIconFallback.DEFAULT,
            emptySet(), recommendedMappings = selected))
        }
      }
    }
  }

  @Test fun matchingDisabledTelegramSettingDoesNotTurnNewChannelIntoAnUpdateTask() {
    val donor = IconPackComponent("org.telegram.messenger")
    val beta = IconPackComponent("org.telegram.messenger.beta", "org.telegram.messenger.DefaultIcon")
    val donorEntry = entry.copy(component = donor,
      light = IconPackArtwork.Resource(IconDrawableName("telegram", "drawable")),
      dark = IconPackArtwork.Resource(IconDrawableName("telegram_night", "drawable")))
    val original = pack.copy(entries = listOf(donorEntry))
    val selected = RecommendedIconMappings(telegramChannels = false)
    val applied = source.copy(pack = original, nativeNames = setOf(donor.packageName),
      generation = IconGenerationManifest(recommendedMappings = selected))
    assertFalse(iconPackNeedsUpdate(original, applied, listOf(beta),
      SystemIconFallback.DEFAULT, emptySet(), recommendedMappings = selected) {
      fail("Disabled channel mapping must not make a missing channel eligible for completion")
      true
    })
    val enabled = applied.copy(generation = IconGenerationManifest())
    assertTrue(iconPackNeedsUpdate(original, enabled, listOf(beta),
      SystemIconFallback.DEFAULT, emptySet(), recommendedMappings = RecommendedIconMappings()) { true })
    val regenerated = enabled.copy(generation = IconGenerationManifest(considered = setOf(beta.flattened)))
    assertFalse(iconPackNeedsUpdate(original, regenerated, listOf(beta),
      SystemIconFallback.DEFAULT, emptySet(), recommendedMappings = RecommendedIconMappings()))
  }

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
  @Test fun newlyInstalledOfficialChannelUsesTheVerifiedCompletionEligibility() {
    val donor = IconPackComponent("org.telegram.messenger")
    val beta = IconPackComponent("org.telegram.messenger.beta", "org.telegram.messenger.DefaultIcon")
    val artwork = IconPackArtwork.Resource(IconDrawableName("telegram", "drawable"))
    val mapped = entry.copy(component = donor, light = artwork,
      dark = IconPackArtwork.Resource(IconDrawableName("telegram_night", "drawable")))
    val original = pack.copy(entries = listOf(mapped))
    val applied = source.copy(pack = original, nativeNames = setOf(donor.packageName))
    assertTrue(iconPackNeedsUpdate(original, applied, listOf(beta),
      SystemIconFallback.DEFAULT, emptySet()) { true })
    assertFalse(iconPackNeedsUpdate(original.copy(entries = listOf(mapped.copy(dark = null))), applied,
      listOf(beta), SystemIconFallback.DEFAULT, emptySet()) { true })
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
    for (version in listOf(1, 2, 3)) {
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
        legacy.copy(schemaVersion = 7, installedOnly = installedOnly,
          recommendedMappings = RecommendedIconMappings.LEGACY).encode()))
      assertFalse(iconPackNeedsUpdate(pack, updated, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), installedOnly = installedOnly, recommendedMappings = RecommendedIconMappings.LEGACY))
    }
  }

  @Test fun componentFilenamePolicyRequiresOneRegenerationWithoutRewritingNativeImports() {
    val old = source.copy(nativeNames = setOf(target.packageName),
      generation = IconGenerationManifest(schemaVersion = 4))
    assertTrue(needed(old))
    assertFalse(needed(old.copy(generation = IconGenerationManifest())))
    assertFalse(needed(old, pack.copy(format = IconPackFormat.MIUI_ICONS), RecommendedIconMappings.LEGACY))
  }

  @Test fun schemaFiveNeedsOneSnapshotRegenerationAndNativeImportsKeepTheirPolicy() {
    val old = source.copy(nativeNames = setOf(target.packageName),
      generation = IconGenerationManifest(schemaVersion = 5, considered = setOf(target.flattened)))
    assertTrue(needed(old))
    val updated = old.copy(generation = IconGenerationManifest(
      considered = setOf(target.flattened), activeLauncherTargets = setOf(target.flattened)))
    assertFalse(needed(updated))
    for (format in listOf(IconPackFormat.MIUI_ICONS, IconPackFormat.MIUI_MTZ)) {
      assertFalse(needed(old, pack.copy(format = format), RecommendedIconMappings.LEGACY))
    }
  }

  @Test fun activeAliasSwitchBypassesPackageCoverageAndPreviouslyConsideredAliasesThenClears() {
    val first = IconPackComponent("os.test.app", "os.foreign.LauncherAndroid")
    val second = first.copy(className = "os.foreign.LauncherApple")
    val snapshot = IconGenerationManifest(
      considered = setOf(first.flattened, second.flattened),
      activeLauncherTargets = setOf(first.flattened))
    val applied = source.copy(nativeNames = setOf(first.packageName), generation = snapshot)
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(second), SystemIconFallback.DEFAULT,
      emptySet()) { fail("An alias switch must not be hidden by coverage or retried rendering"); false })
    val regenerated = applied.copy(generation = IconGenerationManifest.decode(
      snapshot.copy(activeLauncherTargets = setOf(second.flattened)).encode()))
    assertFalse(iconPackNeedsUpdate(pack, regenerated, listOf(second),
      SystemIconFallback.DEFAULT, emptySet()))
  }

  @Test fun addingOrRemovingAnEnabledAliasRequiresRegenerationEvenWithComponentCoverage() {
    val first = IconPackComponent("os.test.app", "os.foreign.First")
    val second = first.copy(className = "os.foreign.Second")
    val applied = source.copy(
      nativeNames = setOf(nativeLauncherActivityName(first)!!, nativeLauncherActivityName(second)!!),
      generation = IconGenerationManifest(
        considered = setOf(first.flattened, second.flattened),
        activeLauncherTargets = setOf(first.flattened, second.flattened)))
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(first), SystemIconFallback.DEFAULT, emptySet()))
    val single = applied.copy(generation = applied.generation!!.copy(
      activeLauncherTargets = setOf(first.flattened)))
    assertTrue(iconPackNeedsUpdate(pack, single, listOf(first, second),
      SystemIconFallback.DEFAULT, emptySet()))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(second, first, first),
      SystemIconFallback.DEFAULT, emptySet()))
  }

  @Test fun aliasComparisonUsesPackageIdentityAndIgnoresPackagesWithoutManagedOutput() {
    val first = IconPackComponent("os.test.app", "os.foreign.Shared")
    val second = first.copy(className = "os.foreign.Other")
    val otherPackage = IconPackComponent("os.test.other", first.className)
    val snapshot = IconGenerationManifest(
      considered = setOf(first.flattened, second.flattened, otherPackage.flattened),
      activeLauncherTargets = setOf(first.flattened, otherPackage.flattened))
    val applied = source.copy(generation = snapshot,
      nativeNames = setOf(nativeLauncherActivityName(otherPackage)!!))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(second, otherPackage),
      SystemIconFallback.DEFAULT, emptySet()) { fail("Unmanaged packages must not retry rendering"); false })
    // Preview entries and stock/legacy class names are not canonical generated output ownership.
    for (names in listOf(emptySet(), setOf(first.className!!))) {
      assertFalse(iconPackNeedsUpdate(pack, applied.copy(nativeNames = names), listOf(second, otherPackage),
        SystemIconFallback.DEFAULT, emptySet()))
    }
  }

  @Test fun aliasSnapshotDoesNotInventChangesForNewInstallsUninstallsOrPackageOnlyTargets() {
    val first = IconPackComponent("os.test.app", "os.foreign.First")
    val other = IconPackComponent("os.test.other", "os.foreign.Other")
    val snapshot = IconGenerationManifest(activeLauncherTargets = setOf(first.flattened))
    val applied = source.copy(nativeNames = setOf(first.packageName, other.packageName), generation = snapshot)
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(first, other),
      SystemIconFallback.DEFAULT, emptySet()))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(other),
      SystemIconFallback.DEFAULT, emptySet()))
    assertFalse(iconPackNeedsUpdate(pack, applied, listOf(first, IconPackComponent(first.packageName)),
      SystemIconFallback.DEFAULT, emptySet()))
    assertFalse(iconPackNeedsUpdate(pack, applied.copy(nativeNames = null), listOf(first.copy(className = "os.foreign.New")),
      SystemIconFallback.DEFAULT, emptySet()))
    assertTrue(iconPackNeedsUpdate(pack, applied, listOf(IconPackComponent(first.packageName)),
      SystemIconFallback.DEFAULT, emptySet()))
  }

  @Test fun nativeImportsDoNotApplyConvertedAliasSnapshotPolicy() {
    val next = target.copy(className = "os.foreign.Next")
    val applied = source.copy(nativeNames = setOf(target.packageName), generation = IconGenerationManifest(
      activeLauncherTargets = setOf(target.flattened)))
    for (format in listOf(IconPackFormat.MIUI_ICONS, IconPackFormat.MIUI_MTZ)) {
      assertFalse(iconPackNeedsUpdate(pack.copy(format = format), applied, listOf(next),
        SystemIconFallback.DEFAULT, emptySet()))
    }
  }

  @Test fun shapePolicyChangesAndVersionTwoResourcesNeedOneUpdateWithoutAnEndlessNotice() {
    val old = source.copy(generation = IconGenerationManifest(
      considered = setOf(target.flattened), schemaVersion = 2))
    assertTrue(iconPackNeedsUpdate(pack, old, listOf(target), SystemIconFallback.DEFAULT, emptySet()))
    for (enabled in listOf(false, true)) {
      val current = old.copy(generation = IconGenerationManifest(
        considered = setOf(target.flattened), completeSystemIconConfig = enabled))
      assertFalse(iconPackNeedsUpdate(pack, current, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), completeSystemIconConfig = enabled))
      assertTrue(iconPackNeedsUpdate(pack, current, listOf(target), SystemIconFallback.DEFAULT,
        emptySet(), completeSystemIconConfig = !enabled))
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

  @Test fun perfectTargetsIncludeUserAppsAndRespectCompletedInventoryWithoutRepeatedUpdates() {
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
      assertEquals(considered.isEmpty(),
        iconPackNeedsUpdate(unmapped, applied, listOf(target), selected, emptySet()))
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
