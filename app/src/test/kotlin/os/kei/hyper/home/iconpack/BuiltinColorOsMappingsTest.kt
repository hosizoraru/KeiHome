package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class BuiltinColorOsMappingsTest {
  private val sourceComponent = IconPackComponent("com.heytap.themestore")
  private val targets = listOf("com.android.thememanager", "com.miui.themestore").map(::IconPackComponent)
  private val theme = IconPackEntry(sourceComponent.flattened, component = sourceComponent,
    light = art("theme_light"), dark = art("theme_dark"))
  private val metadata = ColorOsIconSource("coloros-monochrome/theme.png", null, false)

  @Test fun bothHyperOsPackagesUseCanonicalArtworkAndPaletteInEveryBuiltinStyle() {
    for (style in listOf("classic", "daylight", "nightshadow", "material", "pebble")) {
      for (artPlus in listOf("on", "off")) {
        val key = "coloros-$style-art-$artPlus"
        val original = pack(key)
        val result = BuiltinColorOsMappings.withAliases(original)
        assertEquals(key, result.builtinKey)
        assertEquals(original.entries, result.entries.take(original.entries.size))
        for (target in targets) {
          val alias = result.entries.single { it.component == target }
          assertEquals(target.flattened, alias.key)
          assertEquals(theme.light, alias.light)
          assertEquals(theme.dark, alias.dark)
          assertEquals(metadata, result.colorOsProfile!!.source(target))
          assertEquals(metadata, result.colorOsProfile.source(target.copy(className = "${target.packageName}.Launcher")))
        }
        assertSame(result, BuiltinColorOsMappings.withAliases(result))
      }
    }
    // Inspiration uses the classic ART+ catalog with a distinct selection ID.
    val inspiration = pack().copy(id = "coloros:inspiration:SYSTEM:source", displayName = "Inspiration")
    assertEquals(targets.toSet(), BuiltinColorOsMappings.withAliases(inspiration).entries
      .mapNotNull { it.component }.filter { it in targets }.toSet())
  }

  @Test fun explicitPackageAndActivityAppfiltersKeepTheirPrecedence() {
    val packageEntry = IconPackEntry("explicit-package", targets[0], light = art("explicit"))
    val activity = IconPackComponent(targets[1].packageName, "com.miui.themestore.Feature")
    val activityEntry = IconPackEntry("explicit-activity", activity, dark = art("feature"))
    val explicitMetadata = ColorOsIconSource(null, "native_theme", true)
    val original = pack().let { it.copy(entries = it.entries + packageEntry + activityEntry,
      colorOsProfile = it.colorOsProfile!!.copy(icons = it.colorOsProfile.icons +
        mapOf(targets[0] to explicitMetadata, activity to explicitMetadata))) }
    val result = BuiltinColorOsMappings.withAliases(original)
    val entries = result.entries.associateBy { it.component }
    assertSame(packageEntry, entries[targets[0]])
    assertSame(activityEntry, entries[activity])
    assertEquals(theme.light, entries[targets[1]]!!.light)
    assertEquals(explicitMetadata, result.colorOsProfile!!.source(targets[0]))
    assertEquals(explicitMetadata, result.colorOsProfile.source(activity))
    assertEquals(metadata, result.colorOsProfile.source(targets[1]))
    assertEquals(result.entries.size, entries.size)
  }

  @Test fun multipleThemeRoleEntriesNeverMakeTheExactAliasesAmbiguous() {
    val oplus = IconPackEntry("oplus", IconPackComponent("com.oplus.themestore"),
      light = art("different_theme"), dark = null)
    val result = BuiltinColorOsMappings.withAliases(pack().copy(entries = listOf(oplus, theme)))
    targets.forEach { target ->
      assertEquals(theme.light, result.entries.single { it.component == target }.light)
      assertEquals(theme.dark, result.entries.single { it.component == target }.dark)
    }
  }

  @Test fun arbitraryThirdPartyPacksAndLabelsDoNotAcquireOemAliases() {
    val original = pack()
    val imported = original.copy(source = IconPackSource.Installed("org.example.icons", 1, 0))
    assertSame(imported, BuiltinColorOsMappings.withAliases(imported))
    val withoutProfile = original.copy(colorOsProfile = null)
    assertSame(withoutProfile, BuiltinColorOsMappings.withAliases(withoutProfile))
    val otherBuiltin = original.copy(builtinKey = "other-builtin")
    assertSame(otherBuiltin, BuiltinColorOsMappings.withAliases(otherBuiltin))
    val result = BuiltinColorOsMappings.withAliases(original)
    assertNull(result.entries.find { it.component == IconPackComponent("org.example.themestore") })
    assertNull(result.entries.find { it.component == IconPackComponent("com.miui.themestore.extra") })
  }

  @Test fun absentOrActivityOnlyCanonicalSourceDoesNotGuessFromOtherThemeEntries() {
    val original = pack()
    for (entries in listOf(
      emptyList(),
      listOf(theme.copy(component = IconPackComponent("com.oplus.themestore"))),
      listOf(theme.copy(component = sourceComponent.copy(className = "com.heytap.themestore.Launcher"))),
      listOf(theme.copy(light = null, dark = null)),
    )) {
      val candidate = original.copy(entries = entries)
      assertSame(candidate, BuiltinColorOsMappings.withAliases(candidate))
    }
  }

  @Test fun mappingRevisionInvalidatesBuiltinPublicationsWithoutChangingOriginalAssets() {
    val original = pack()
    val mapped = BuiltinColorOsMappings.withAliases(original)
    val identity = IconBuildInventory.sourceIdentity(mapped)
    assertEquals(original.id, mapped.id)
    assertSame(original.source, mapped.source)
    assertEquals("${original.id}\nsource:${BuiltinColorOsMappings.REVISION}", identity)
    assertEquals(identity, IconBuildInventory.decodeSource(identity.toByteArray()))
    assertEquals("${original.id}\nsource",
      IconBuildInventory.sourceIdentity(original.copy(builtinKey = "other-builtin")))
  }

  private fun pack(key: String = "coloros-classic-art-on"): IconPackDescriptor {
    val record = BuiltinIconPack(key, "source", emptySet()) { _, _, _ -> error("No asset reads for exact aliases") }
    return IconPackDescriptor("source", key, IconPackFormat.CLASSIC_ZIP, IconPackSource.Builtin(record),
      listOf(theme), colorOsProfile = ColorOsPackProfile(null, "coloros-mask.png",
        mapOf(sourceComponent to metadata), builtin = record), builtinKey = key)
  }

  private fun art(name: String) = IconPackArtwork.Resource(IconDrawableName(name))
}
