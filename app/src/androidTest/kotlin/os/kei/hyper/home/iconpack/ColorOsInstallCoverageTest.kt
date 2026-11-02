package os.kei.hyper.home.iconpack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorOsInstallCoverageTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun knownRuntimeMappingsAreExportedBeforeAnApplicationAppearsInTheLauncherInventory() = runBlocking {
    val pack = IconPackRepository(context).listBuiltins().packs.single { it.builtinKey == "coloros-classic-art-on" }
    for (dark in listOf(false, true)) {
      val result = IconPackExporter(context, IconPackLimits()).export(pack, emptyList(), dark, 192) {}
      try {
        ZipFile(result.archive).use { zip ->
          for (name in listOf("com.coolapk.market", "com.xiaomi.smarthome", "com.taobao.taobao", "tv.danmaku.bili")) {
            assertNotNull("Declared $name must survive an empty installed-app inventory",
              zip.getEntry("res/drawable-xxhdpi/$name.png"))
            assertNotNull("The actual OEM monochrome source must survive", result.monochrome[name])
          }
        }
      } finally { result.archive.delete() }
    }
  }

  @Test fun artOffKeepsAnInstalledAppsOriginalDayIconAndTheOemNightRedraw() = runBlocking {
    val pack = IconPackRepository(context).listBuiltins().packs.single { it.builtinKey == "coloros-classic-art-off" }
    val target = IconPackComponent("com.coolapk.market", "com.coolapk.market.view.main.MainActivity")
    val results = listOf(false, true).map { dark ->
      IconPackExporter(context, IconPackLimits()).export(pack, listOf(target), dark, 192) {}
    }
    try {
      val bytes = results.map { result -> ZipFile(result.archive).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry("res/drawable-xxhdpi/com.coolapk.market.png"))).use { it.readBytes() }
      } }
      assertFalse("ART-off must retain the independent OEM night resource", bytes[0].contentEquals(bytes[1]))
    } finally { results.forEach { it.archive.delete() } }
  }
}
