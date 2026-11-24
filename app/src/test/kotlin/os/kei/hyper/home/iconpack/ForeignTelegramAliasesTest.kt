package os.kei.hyper.home.iconpack

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignTelegramAliasesTest {
  private val launcher = "org.telegram.ui.LaunchActivity"
  private val target = IconPackComponent("example.telegram", launcher)
  private val exact = "${target.packageName}#$launcher"
  private val foreignPath = "layer_animating_icons/$launcher/config.xml"

  @Test fun suppliesBothModesForTheExactForeignComponent() {
    val light = png(1)
    val dark = png(2)
    val additions = ForeignTelegramAliases.additions(sequenceOf(foreignPath),
      listOf(target, target, IconPackComponent("org.telegram.messenger", launcher)), nativeLayers = true, check = {},
      read = { error("Foreign artwork must never be copied") }) { component, night ->
      assertEquals(target, component)
      if (night) dark else light
    }
    assertTrue(additions.keys.all { CustomIconResources.owner(it) in setOf(exact, target.packageName) })
    assertArrayEquals(light, additions.getValue("res/drawable-xxhdpi/$exact.png"))
    assertArrayEquals(dark, additions.getValue("nightmode/res/drawable-xxhdpi/$exact.png"))
    assertArrayEquals(light, additions.getValue("layer_animating_icons/${target.packageName}/0/quiet/quietImage.png"))
    assertArrayEquals(dark, additions.getValue("fancy_icons/${target.packageName}/night.png"))
  }

  @Test fun preservesAnAuthoredPackageClosureAndAnExistingExactMapping() {
    val packageRoot = "layer_animating_icons/${target.packageName}"
    val program = "<Icon src=\"$packageRoot/icon.png\"/>".toByteArray()
    val paths = sequenceOf(foreignPath, "$packageRoot/config.xml")
    val additions = ForeignTelegramAliases.additions(paths, listOf(target), nativeLayers = true, check = {}, read = { path ->
      assertEquals("$packageRoot/config.xml", path)
      program
    }) { _, _ -> error("Use authored artwork") }
    assertEquals(setOf("layer_animating_icons/$exact/config.xml"), additions.keys)
    assertEquals("<Icon src=\"layer_animating_icons/$exact/icon.png\"/>", additions.values.single().decodeToString())
    assertTrue(ForeignTelegramAliases.additions(paths + "res/drawable/$exact.png", listOf(target), nativeLayers = true, check = {},
      read = { error("Keep exact artwork") }) { _, _ -> error("Keep exact artwork") }.isEmpty())
  }

  @Test fun convertedArchivesReceiveTheClosedSelectorSupportedByLocalValidation() {
    val image = png(1)
    val additions = ForeignTelegramAliases.additions(emptySequence(), listOf(target), nativeLayers = false, check = {},
      read = { error("No authored resources") }) { _, _ -> image }
    assertEquals(setOf(
      "res/drawable-xxhdpi/$exact.png",
      "nightmode/res/drawable-xxhdpi/$exact.png",
      "res/drawable-xxhdpi/${target.packageName}.png",
      "nightmode/res/drawable-xxhdpi/${target.packageName}.png",
      "fancy_icons/${target.packageName}/manifest.xml",
      "fancy_icons/${target.packageName}/day.png",
    ), additions.keys)
  }

  private fun png(marker: Byte): ByteArray = ByteBuffer.allocate(33)
    .putLong(0x89504e470d0a1a0aUL.toLong()).putInt(13).putInt(0x49484452)
    .putInt(64).putInt(64).put(marker).array()
}
