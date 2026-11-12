package os.kei.hyper.home.iconpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramChannelIconsTest {
  private val store = "org.telegram.messenger"
  private val web = "org.telegram.messenger.web"
  private val beta = "org.telegram.messenger.beta"
  private val families = listOf(store, web, beta)
  private val legacy = "org.telegram.ui.LaunchActivity"

  @Test fun disabledMappingNeverSelectsDonorsOrQueriesExistingOrDonorNightProof() {
    for (target in families) {
      val donor = componentEntry(families.first { it != target })
      val own = componentEntry(target)
      val shared = resource("shared_day_night")
      val entries = listOf(donor, own)
      val icons = TelegramChannelIcons(entries, enabled = false)
      for (alias in listOf<String?>(null, ".DefaultIcon", "$store.DefaultIcon", legacy)) {
        for (dark in listOf(false, true)) {
          for (existing in listOf(null, own, own.copy(light = shared, dark = shared), own.copy(dark = null))) {
            assertNull(icons.donor(IconPackComponent(target, alias), dark, existing) {
              error("Disabled mappings must not query night proof for existing icons or donors")
            })
          }
        }
      }
      assertEquals(listOf(donor, own), entries)
    }
  }

  @Test fun reEnablingRestoresMissingDefaultArtworkWhileKeepingOwnAndAlternateIcons() {
    val donor = componentEntry(store)
    val own = componentEntry(web).copy(light = resource("own_day"), dark = resource("own_night"))
    val entries = listOf(donor, own)
    for (enabled in listOf(true, false, true)) {
      val icons = TelegramChannelIcons(entries, enabled)
      for (dark in listOf(false, true)) {
        assertSame(if (enabled) donor else null, icons.donor(IconPackComponent(beta), dark, null))
        assertNull(icons.donor(IconPackComponent(web), dark, own))
        assertNull(icons.donor(IconPackComponent(beta, ".BlueIcon"), dark, null) {
          error("Selected alternate icons must not query donors")
        })
      }
      assertSame(own, entries.single { it.component == IconPackComponent(web) })
      assertEquals(resource("own_day"), own.light)
      assertEquals(resource("own_night"), own.dark)
    }
  }

  @Test fun offlineTargetsExposeOnlyTheThreePackageTargetsInFamilyPriorityOrder() {
    val expected = listOf(IconPackComponent(store), IconPackComponent(web), IconPackComponent(beta))
    assertEquals(expected, TelegramChannelIcons.offlineTargets())
    assertTrue(TelegramChannelIcons.offlineTargets().all(TelegramChannelIcons::isDefaultTarget))
  }

  @Test fun allThreeChannelsCanDonateToEachOtherInBothModes() {
    for (source in families) for (target in families.filter { it != source }) {
      val entry = componentEntry(source)
      val icons = TelegramChannelIcons(listOf(entry))
      for (dark in listOf(false, true)) {
        assertSame(entry, icons.donor(IconPackComponent(target), dark, null))
        assertNull(icons.donor(IconPackComponent(source), dark, null))
      }
    }
  }

  @Test fun defaultAliasesAcceptRelativeChannelNamesAndSharedLauncherNames() {
    for (target in families) {
      val source = families.first { it != target }
      val entry = componentEntry(source)
      val icons = TelegramChannelIcons(listOf(entry))
      for (alias in listOf(".DefaultIcon", "$target.DefaultIcon", "$store.DefaultIcon", legacy)) {
        assertTrue(TelegramChannelIcons.isDefaultTarget(IconPackComponent(target, alias)))
        assertSame(entry, icons.donor(IconPackComponent(target, alias), true, null))
        assertNull(icons.donor(
          IconPackComponent(target, alias), false, componentEntry(target, alias).copy(dark = null),
        ))
      }
    }
  }

  @Test fun partialTargetKeepsExplicitLightAndCanBorrowNight() {
    val light = resource("own_light")
    val existing = componentEntry(web).copy(light = light, dark = light)
    val donor = componentEntry(store)
    val icons = TelegramChannelIcons(listOf(donor, existing))

    assertNull(icons.donor(IconPackComponent(web), false, existing))
    assertSame(donor, icons.donor(IconPackComponent(web), true, existing))
    assertSame(donor, icons.donor(IconPackComponent(web), false, existing.copy(light = null)))
  }

  @Test fun explicitNightAndNightOnlyTargetsKeepTheirOwnNight() {
    val donor = componentEntry(store)
    val icons = TelegramChannelIcons(listOf(donor))
    val existing = componentEntry(web)

    assertNull(icons.donor(IconPackComponent(web), true, existing))
    assertNull(icons.donor(IconPackComponent(web), true, existing.copy(light = null)))
    assertSame(donor, icons.donor(IconPackComponent(web), false, existing.copy(light = null)))
  }

  @Test fun lightOnlyDonorsAreRejectedEvenWhenLightIsMissing() {
    val light = resource("light_only")
    for (entry in listOf(
      componentEntry(store).copy(dark = null),
      componentEntry(store).copy(light = light, dark = light),
      nativeEntry(store).copy(light = archive("res/drawable/icon.png"), dark = null),
    )) {
      val icons = TelegramChannelIcons(listOf(entry))
      assertFalse(TelegramChannelIcons.declaredNight(entry))
      assertNull(icons.donor(IconPackComponent(web), true, null))
      assertNull(icons.donor(IconPackComponent(web), false, null))
    }
  }

  @Test fun missingLightRequiresDonorLightAndNightProofInTheSameEntry() {
    val nightOnly = componentEntry(store).copy(light = null)
    val complete = componentEntry(beta)
    val icons = TelegramChannelIcons(listOf(nightOnly, complete))

    assertSame(nightOnly, icons.donor(IconPackComponent(web), true, null))
    assertSame(complete, icons.donor(IconPackComponent(web), false, null))
    assertNull(TelegramChannelIcons(listOf(nightOnly)).donor(IconPackComponent(web), false, null))
  }

  @Test fun resourceAwareCallbackProvesSharedCompiledDrawableForDonorsAndExistingTargets() {
    val shared = resource("configured_by_android")
    val donor = componentEntry(store).copy(light = shared, dark = shared)
    val existing = componentEntry(web).copy(light = shared, dark = shared)
    val icons = TelegramChannelIcons(listOf(donor))
    val target = IconPackComponent(web)

    assertNull(icons.donor(target, true, null))
    assertSame(donor, icons.donor(target, true, null) { it === donor })
    assertSame(donor, icons.donor(target, false, null) { it === donor })
    assertNull(icons.donor(target, true, existing) { it === existing || it === donor })
    assertNull(icons.donor(target, false, existing) { error("Explicit light needs no night query") })
    assertNull(icons.donor(target, true, null) { false })
  }

  @Test fun declaredNightDetectsSharedArchiveQualifiersNativePathsAndLayerVariants() {
    for (artwork in listOf(
      archive("res/drawable-night/icon.png", IconNightMode.DARK),
      archive("nightmode/res/drawable/icon.png"),
      IconPackArtwork.Archive(emptyList(), layers = listOf(listOf(
        variant("nightmode/res/drawable/icon/0.png"),
      ))),
    )) {
      val entry = componentEntry(store).copy(light = artwork, dark = artwork)
      assertTrue(TelegramChannelIcons.declaredNight(entry))
      assertSame(entry, TelegramChannelIcons(listOf(entry)).donor(IconPackComponent(web), true, null))
      val existing = entry.copy(component = IconPackComponent(web))
      assertNull(TelegramChannelIcons(listOf(entry)).donor(IconPackComponent(web), true, existing))
    }
    val day = archive("res/drawable-notnight/icon.png", IconNightMode.LIGHT)
    assertFalse(TelegramChannelIcons.declaredNight(componentEntry(store).copy(light = day, dark = day)))
  }

  @Test fun forksTgxAndCustomTargetAliasesNeverParticipate() {
    val donor = componentEntry(store)
    for (packageName in listOf(
      "org.thunderdog.challegram", "org.telegram.plus", "org.telegram.messenger.beta.debug",
      "org.telegram.messenger.fake", "org.telegram.messenger.Web", "tw.nekomimi.nekogram",
    )) {
      assertFalse(TelegramChannelIcons.isFamily(packageName))
      assertNull(TelegramChannelIcons(listOf(donor)).donor(IconPackComponent(packageName), true, null))
      assertNull(TelegramChannelIcons(listOf(componentEntry(packageName)))
        .donor(IconPackComponent(web), true, null))
    }
    for (target in families) for (alias in listOf(
      ".BlueIcon", ".VintageIcon", ".AquaIcon",
      "$store.VintageIcon", "$store.AquaIcon", "$target.VintageIcon", "$target.AquaIcon",
      "$target.DefaultIconExtra", "other.DefaultIcon",
      "org.telegram.ui.LaunchActivityExtra", "",
    )) {
      assertFalse(TelegramChannelIcons.isDefaultTarget(IconPackComponent(target, alias)))
      for (dark in listOf(false, true)) {
        assertNull(TelegramChannelIcons(listOf(donor)).donor(IconPackComponent(target, alias), dark, null) {
          error("Named alternate targets must be excluded before querying donors")
        })
      }
    }
  }

  @Test fun alternateDonorMappingsAndUnownedNativeNamesAreRejected() {
    for (entry in listOf(
      componentEntry(store, "$store.BlueIcon"),
      componentEntry(beta, ".VintageIcon"),
      nativeEntry("$store.BlueIcon"),
      nativeEntry("$store#$store.BlueIcon"),
      nativeEntry("$store#other.DefaultIcon"),
      nativeEntry("$store#$store.DefaultIcon#extra"),
      nativeEntry(legacy),
      nativeEntry("$store/.$store.DefaultIcon"),
      nativeEntry("$store ").copy(key = store),
      nativeEntry(store).copy(component = IconPackComponent(store, ".BlueIcon")),
    )) {
      assertNull(TelegramChannelIcons(listOf(entry)).donor(IconPackComponent(web), true, null))
    }
  }

  @Test fun familyOrderAndDirectPackageBeforeDefaultBeforeLegacyAreDeterministic() {
    for (target in families) {
      val ordered = families.filter { it != target }
      val direct = componentEntry(ordered.first()).copy(key = "z_direct")
      val default = componentEntry(ordered.first(), ".DefaultIcon").copy(key = "a_default")
      val old = componentEntry(ordered.first(), legacy).copy(key = "0_legacy")
      val other = componentEntry(ordered.last()).copy(key = "0_other")
      for (entries in listOf(listOf(other, old, default, direct), listOf(direct, default, old, other))) {
        val icons = TelegramChannelIcons(entries)
        for (dark in listOf(false, true)) {
          assertSame(direct, icons.donor(IconPackComponent(target), dark, null))
          assertSame(default, TelegramChannelIcons(entries - direct)
            .donor(IconPackComponent(target), dark, null))
          assertSame(old, TelegramChannelIcons(entries - direct - default)
            .donor(IconPackComponent(target), dark, null))
        }
      }
    }
  }

  @Test fun equivalentDefaultAliasesUseStableKeysRegardlessOfInputOrder() {
    val first = componentEntry(store, ".DefaultIcon").copy(key = "a")
    val second = componentEntry(store, "$store.DefaultIcon").copy(key = "b")
    for (entries in listOf(listOf(first, second), listOf(second, first))) {
      assertSame(first, TelegramChannelIcons(entries).donor(IconPackComponent(web), true, null))
    }
  }

  @Test fun nativePackagesCanonicalQualifiedAliasesAndOwnedDefaultStemsCanDonate() {
    for (source in families) {
      val target = families.first { it != source }
      for (name in listOf(
        source, "$source.DefaultIcon", "$source#$source.DefaultIcon",
        "$source#$store.DefaultIcon", "$source#.DefaultIcon", "$source#$legacy",
      )) {
        val entry = nativeEntry(name)
        for (dark in listOf(false, true)) {
          assertSame(entry, TelegramChannelIcons(listOf(entry)).donor(IconPackComponent(target), dark, null))
        }
      }
    }
  }

  @Test fun indexCopiesMembershipAndReturnsExactlyOneModeSpecificDonor() {
    val dayMissing = componentEntry(store).copy(light = null)
    val complete = componentEntry(beta)
    val entries = mutableListOf(dayMissing, complete)
    val icons = TelegramChannelIcons(entries)
    entries.clear()
    var calls = 0
    assertSame(dayMissing, icons.donor(IconPackComponent(web), true, null) { calls++; true })
    assertTrue(calls == 1)
    assertSame(complete, icons.donor(IconPackComponent(web), false, null))
  }

  private fun componentEntry(packageName: String, className: String? = null) = IconPackEntry(
    IconPackComponent(packageName, className).flattened,
    component = IconPackComponent(packageName, className),
    light = resource("light"), dark = resource("night"),
  )

  private fun nativeEntry(name: String) = IconPackEntry(
    name, nativeName = name, light = resource("light"), dark = resource("night"),
  )

  private fun resource(name: String) = IconPackArtwork.Resource(IconDrawableName(name))

  private fun variant(path: String, night: IconNightMode = IconNightMode.ANY) =
    IconArchiveVariant(path, IconResourceQualifiers("drawable", night))

  private fun archive(path: String, night: IconNightMode = IconNightMode.ANY) =
    IconPackArtwork.Archive(listOf(variant(path, night)))
}
