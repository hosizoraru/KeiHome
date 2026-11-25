package os.kei.hyper.home.iconpack

import org.junit.Assert.*
import org.junit.Test

class CustomIconResourcesTest {
  @Test fun nativeNamesIncludePackageObservedAndQualifiedLauncherAliases() {
    val names = CustomIconResources.nativeNames(
      packageName = "com.example.shop",
      targets = listOf(IconPackComponent("com.example.shop", ".MainActivity")),
      launcherClassNames = listOf("com.example.shop.Welcome"),
    )
    assertEquals(
      setOf(
        "com.example.shop",
        "com.example.shop.MainActivity",
        "com.example.shop.Welcome",
      ),
      names,
    )
  }

  @Test fun capturesOnlyTheExactIconWithoutGlobalMasksOrOtherChannels() {
    val selected = CustomIconResources.select(mapOf(
      "res/drawable/com.source.png" to byteArrayOf(1),
      "res/drawable/com.source.extra.png" to byteArrayOf(2),
      "res/drawable/com.other.png" to byteArrayOf(3),
      "icon_config.xml" to byteArrayOf(4)), listOf("com.source"))
    assertEquals(setOf("res/drawable/custom.icon.png"), selected.keys)
  }

  @Test fun preservesBothAppearancesDensityAndProgramsWhenRetargeted() {
    val bytes = byteArrayOf(3, 7, 11)
    val captured = CustomIconResources.select(mapOf(
      "res/drawable-xxhdpi/com.source.png" to bytes,
      "nightmode/res/drawable/com.source.png" to bytes,
      "fancy_icons/com.source/day.png" to bytes,
      "layer_animating_icons/com.source/image.png" to bytes,
      "animating_icons/com.source/manifest.xml" to
        "<Image src=\"fancy_icons/com.source/day.png\"/>".toByteArray()), listOf("com.source"))
    val result = CustomIconResources.retarget(captured, setOf("com.target", "com.target#android.app.Main"))
    assertEquals(10, result.size)
    assertSame(bytes, result.getValue("nightmode/res/drawable/com.target.png"))
    assertEquals("<Image src=\"fancy_icons/com.target/day.png\"/>",
      result.getValue("animating_icons/com.target/manifest.xml").toString(Charsets.UTF_8))
    assertTrue("layer_animating_icons/com.target#android.app.Main/image.png" in result)
    assertFalse(result.keys.any { it.contains("/android.app.Main/") })
  }

  @Test fun prefersAnExactActivityIconOverThePackageFallback() {
    val result = CustomIconResources.select(mapOf(
      "res/drawable/com.source.png" to byteArrayOf(1),
      "res/drawable/com.source.Main.png" to byteArrayOf(2)),
      listOf("com.source.Main", "com.source"))
    assertArrayEquals(byteArrayOf(2), result.getValue("res/drawable/custom.icon.png"))
  }

  @Test fun supportsQuietIconsAtTheModuleRoot() {
    val result = CustomIconResources.retarget(CustomIconResources.select(
      mapOf("com.source.png" to byteArrayOf(8)), listOf("com.source")), setOf("com.target"))
    assertArrayEquals(byteArrayOf(8), result.getValue("com.target.png"))
  }

  @Test fun rewritesQualifiedReferencesWithoutChangingNeighbouringNames() {
    val captured = CustomIconResources.select(mapOf(
      "fancy_icons/com.source/manifest.xml" to
        """<Icon root="fancy_icons/com.source" src="nightmode/res/drawable-xhdpi/com.source.png"
          other="res/drawable/com.source.extra.png"/>""".toByteArray()),
      listOf("com.source"))
    val xml = CustomIconResources.retarget(captured, setOf("com.target"))
      .getValue("fancy_icons/com.target/manifest.xml").toString(Charsets.UTF_8)
    assertTrue(xml.contains("""root="fancy_icons/com.target""""))
    assertTrue(xml.contains("nightmode/res/drawable-xhdpi/com.target.png"))
    assertTrue(xml.contains("res/drawable/com.source.extra.png"))
  }

  @Test(expected = IllegalArgumentException::class) fun rejectsTraversalInsideAnOwnedProgram() {
    CustomIconResources.select(mapOf("fancy_icons/com.source/../secret.png" to byteArrayOf(1)), listOf("com.source"))
  }

  @Test(expected = IllegalArgumentException::class) fun cannotInjectGlobalConfigurationThroughASnapshot() {
    CustomIconResources.retarget(mapOf("icon_config.xml" to byteArrayOf(1)), setOf("com.target"))
  }

  @Test(expected = IconPackException::class) fun missingSourceCannotSilentlyFallBackToAnotherApp() {
    CustomIconResources.select(mapOf("res/drawable/com.other.png" to byteArrayOf(1)), listOf("com.source"))
  }
}
