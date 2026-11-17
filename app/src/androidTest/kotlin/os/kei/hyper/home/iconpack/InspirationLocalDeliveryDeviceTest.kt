package os.kei.hyper.home.iconpack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import os.kei.hyper.home.data.ColorOsStyle
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.data.RecommendedIconMappings
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.status.IconPackHandoffs
import os.kei.hyper.home.xposed.ConvertedIconArchive

@RunWith(AndroidJUnit4::class)
class InspirationLocalDeliveryDeviceTest {
  @Test fun handoffIncludesNightFallbacksWithGenericColoringOnAndOff(): Unit = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val repository = IconPackRepository(context)
    val base = requireNotNull(resolveBuiltinIconPack("coloros-classic-art-on", emptyList(), repository::listBuiltins))
    val pack = base.copy(id = ColorOsStyle.INSPIRATION.importSourceId(base.id, IconPackAppearance.SYSTEM))
    val weather = IconPackComponent("com.coloros.weather.service")
    for (generic in listOf(false, true)) {
      val handoff = IconPackHandoffs.prepare(context, repository, IconPackPreviewRepository(context), pack,
        listOf(IconPackComponent("com.android.bluetooth"), weather), IconPackAppearance.SYSTEM, includePalette = true,
        systemFallback = SystemIconFallback.DEFAULT, recommendedMappings = RecommendedIconMappings.NONE,
        applyCustomIcons = false, monetUnadaptedIcons = generic)
      val nested = File(context.cacheDir, "inspiration-local-${UUID.randomUUID()}.zip")
      try {
        assertEquals("CONVERTED_CLASSIC_ZIP", handoff.convertedOrigin)
        ConvertedIconArchive.inspect(handoff.file, requireNotNull(handoff.convertedOrigin), context.cacheDir)
        ZipFile(handoff.file).use { mtz ->
          mtz.getInputStream(requireNotNull(mtz.getEntry("icons"))).use { input ->
            nested.outputStream().use { input.copyTo(it) }
          }
        }
        ZipFile(nested).use { icons ->
          val stem = "res/drawable-xxhdpi/${weather.packageName}.png"
          assertNotNull("The reported offline Weather alias must be exported", icons.getEntry(stem))
          if (generic || icons.getEntry("fancy_icons/${weather.packageName}/manifest.xml") != null) {
            assertNotNull("A palette icon also needs its night fallback", icons.getEntry("nightmode/$stem"))
          }
        }
      } finally { handoff.file.delete(); nested.delete() }
    }
  }
}
