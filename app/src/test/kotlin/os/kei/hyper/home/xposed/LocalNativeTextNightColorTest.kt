package os.kei.hyper.home.xposed

import org.junit.Assert.*
import org.junit.Test
import os.kei.hyper.home.iconpack.NativeIconPngFixtures
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.perfect.PerfectDynamicIconValidator

class LocalNativeTextNightColorTest {
  @Test fun canonicalGeneratedTextColorPassesStrictLayerSanitizerWithDarkMode() {
    val color = requireNotNull(PerfectDynamicIconValidator.nightTextColor("#80181818"))
    admit("<Text color='$color'/>")
  }

  @Test fun generatedTextColorRequiresRegisteredUnshadowedDarkMode() {
    val color = requireNotNull(PerfectDynamicIconValidator.nightTextColor("#000000"))
    rejects("<Text color='$color'/>", "DateTime.Day")
    rejects("<Var name='__darkmode' expression='1'/><Text color='$color'/>")
  }

  @Test fun arbitraryColorExpressionsAndNonTextGeneratedColorsStayRejected() {
    val color = requireNotNull(PerfectDynamicIconValidator.nightTextColor("#000000"))
    rejects("<Text color='${color.replace(",216,", ",217,")}'/>")
    rejects("<Text color='${color.replace("#__darkmode", "#date")}'/>")
    rejects("<Text color='#000000' colorExp='1'/>")
    rejects("<FolmeState name='state' color='$color'/>")
  }

  @Test fun authoredLiteralBlueAndArgbColorsKeepPassingWithoutDarkMode() {
    admit("<Text color='#3482FF'/>", "DateTime.Day")
    admit("<Text color='#80000000'/>", "DateTime.Day")
  }

  private fun rejects(body: String, updater: String = "DateTime.Day,DarkMode") {
    assertThrows(IconPackException::class.java) { admit(body, updater) }
  }

  private fun admit(body: String, updater: String = "DateTime.Day,DarkMode") {
    val base = "layer_animating_icons/com.example/"
    val visuals = body.replace("<Text ",
      "<Text name='weekday' size='44' fontFamily='mipro-semibold' textExp=\"formatDate('E',#time_sys)\" ")
    val files = mapOf(
      base + "config.xml" to
        "<Config><Variables><Variable name='layer0' value='0'/></Variables></Config>".toByteArray(),
      base + "0/quiet/quietImage.png" to NativeIconPngFixtures.rgba(4),
      base + "0/fancy/manifest.xml" to
        "<Icon width='351' height='351' useVariableUpdater='$updater'>$visuals</Icon>".toByteArray(),
    )
    val consumed = LocalLayerIconValidator.validate(files.keys, { path, _ -> files.getValue(path) }, {})
    assertEquals(files.keys, consumed)
  }
}
