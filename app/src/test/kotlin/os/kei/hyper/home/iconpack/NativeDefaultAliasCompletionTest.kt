package os.kei.hyper.home.iconpack

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NativeDefaultAliasCompletionTest {
  private val pkg = "os.test.defaulticon"
  private val target = IconPackComponent(pkg, "$pkg.DefaultLauncherIcon")
  private val proof = NativeDefaultAliasCompletion.Identity(pkg, pkg, 0x7f080123, 0x7f080123, true)
  private val closure = setOf(
    "$pkg.png",
    "nightmode/$pkg.png",
    "res/drawable-xxhdpi/$pkg.png",
    "res/drawable-xhdpi/$pkg/0.png",
    "res/drawable-xhdpi/$pkg/1.png",
    "nightmode/res/drawable-xhdpi/$pkg/0.png",
    "fancy_icons/$pkg/manifest.xml",
    "fancy_icons/$pkg/keihome_night/num_31.png",
    "nightmode/fancy_icons/$pkg/manifest.xml",
    "animating_icons/$pkg/0.png",
    "layer_animating_icons/$pkg/quietimage.png",
    "layer_animating_icons/$pkg/0/fancy/manifest.xml",
    "layer_animating_icons/$pkg/0/fancy/bg.png",
    "layer_animating_icons/$pkg/1/fancy/manifest.xml",
    "nightmode/layer_animating_icons/$pkg/1/fancy/num_31.png",
  )

  @Test fun identicalEffectiveResourceCopiesWholeClosureUsingNativeFilename() {
    val unrelated = "fancy_icons/os.other.icon/manifest.xml"
    val result = NativeDefaultAliasCompletion.plan(closure + unrelated, listOf(target), emptySet(), { proof })
    val alias = result.single()
    assertEquals(target.className, alias.name)
    assertEquals(closure, alias.paths.values.toSet())
    closure.forEach { source ->
      assertEquals(source, alias.paths[source.replace(pkg, alias.name)])
    }
    assertFalse(alias.paths.values.contains(unrelated))
  }

  @Test fun foreignActivityNamespaceUsesPackageHashClassRatherThanBareClass() {
    val foreign = IconPackComponent(pkg, "org.vendor.LauncherIcon")
    val alias = NativeDefaultAliasCompletion.plan(closure, listOf(foreign), emptySet(), { proof }).single()
    assertEquals("$pkg#org.vendor.LauncherIcon", alias.name)
    assertTrue(alias.paths.containsKey("fancy_icons/${alias.name}/manifest.xml"))
    assertFalse(alias.paths.keys.any { "/org.vendor.LauncherIcon/" in it })
  }

  @Test fun distinctResourcesOtherOwnersUnknownAndNonLauncherNeverComplete() {
    for (identity in listOf(null, proof.copy(applicationIcon = 0),
      proof.copy(effectiveActivityIcon = 0x7f080124),
      proof.copy(activityPackage = "os.other.package"),
      proof.copy(applicationPackage = "os.other.package"), proof.copy(launcher = false))) {
      assertTrue(NativeDefaultAliasCompletion.plan(closure, listOf(target), emptySet(), { identity }).isEmpty())
    }
  }

  @Test fun anyExactAuthoredAliasTreeIncludingNightOnlyPreventsAllGrafting() {
    for (path in closure) {
      val authored = path.replace(pkg, requireNotNull(target.className))
      var queried = false
      val result = NativeDefaultAliasCompletion.plan(closure + authored, listOf(target), emptySet(), {
        queried = true; proof
      })
      assertTrue("Authored $authored", result.isEmpty())
      assertFalse(queried)
    }
  }

  @Test fun legacyExplicitForeignAliasAlsoRemainsAuthoritative() {
    val foreign = IconPackComponent(pkg, "org.vendor.LauncherIcon")
    val paths = closure + "nightmode/fancy_icons/org.vendor.LauncherIcon/quietimage.png"
    assertTrue(NativeDefaultAliasCompletion.plan(paths, listOf(foreign), emptySet(), { proof }).isEmpty())
  }

  @Test fun preservedPackagesAndMissingDonorsDoNotEvenQueryIdentity() {
    val query: (IconPackComponent) -> NativeDefaultAliasCompletion.Identity? = {
      fail("No identity query expected"); null
    }
    assertTrue(NativeDefaultAliasCompletion.plan(closure, listOf(target), setOf(pkg), query).isEmpty())
    assertTrue(NativeDefaultAliasCompletion.plan(emptySet(), listOf(target), emptySet(), query).isEmpty())
    assertTrue(NativeDefaultAliasCompletion.plan(closure, listOf(IconPackComponent(pkg)), emptySet(), query).isEmpty())
  }

  @Test fun entryBudgetNeverProducesPartialClosureAndDuplicateTargetsCopyOnce() {
    val limits = IconPackLimits(maxEntries = closure.size * 2 - 1)
    assertTrue(NativeDefaultAliasCompletion.plan(closure, listOf(target), emptySet(), { proof }, limits).isEmpty())
    val complete = NativeDefaultAliasCompletion.plan(closure, listOf(target, target), emptySet(), { proof },
      limits.copy(maxEntries = closure.size * 2))
    assertEquals(closure.size, complete.single().paths.size)
  }

  @Test fun cancellationPropagatesDuringEnumerationAndAfterMetadataLookup() {
    var calls = 0
    assertThrows(CancellationException::class.java) {
      NativeDefaultAliasCompletion.plan(closure, listOf(target), emptySet(), { proof }, check = {
        if (++calls == 4) throw CancellationException("cancel enumeration")
      })
    }
    var lookedUp = false
    assertThrows(CancellationException::class.java) {
      NativeDefaultAliasCompletion.plan(closure, listOf(target), emptySet(), {
        lookedUp = true; proof
      }, check = { if (lookedUp) throw CancellationException("cancel after PM") })
    }
  }

  @Test fun nativeExportPolicyChangesBuildIdentityWhileApkIdentityStaysStable() {
    val record = StoredIconPack("import-id", "perfect.zip", 0, IconPackFormat.MIUI_ICONS,
      File("fixture.zip"), null, 1, null)
    val pack = IconPackDescriptor("source-id", "Source", IconPackFormat.MIUI_ICONS,
      IconPackSource.Imported(record), emptyList())
    val previous = "source-id\nimport-id"
    val current = IconBuildInventory.sourceIdentity(pack)
    assertEquals("$previous:${NativeDefaultAliasCompletion.REVISION}", current)
    assertEquals(current, IconBuildInventory.decodeSource(current.toByteArray()))
    assertEquals("$previous:palette-apk-1", IconBuildInventory.sourceIdentity(pack.copy(format = IconPackFormat.APK)))
    assertTrue(IconBuildInventory.sourceIdentity(pack.copy(format = IconPackFormat.MIUI_MTZ))
      .endsWith(":${NativeDefaultAliasCompletion.REVISION}"))
  }
}
