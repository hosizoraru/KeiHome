package os.kei.hyper.home.iconpack

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback

class DownloadSystemFallbackDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val ui = IconPackComponent("com.android.providers.downloads.ui",
    "com.android.providers.downloads.ui.DownloadList")
  private val host = IconPackComponent("com.android.providers.downloads")

  @Test fun signedDataInstalledDownloadUiUsesNativeSystemArtwork() {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(ui.packageName, PackageManager.ApplicationInfoFlags.of(0))
    assumeTrue("This fixture exercises a data-installed system companion",
      info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)
    assertEquals(PackageManager.SIGNATURE_MATCH, pm.checkSignatures(ui.packageName, host.packageName))
    val renderer = SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)
    assertTrue(renderer.isSystem(ui))
    assertFalse(renderer.isSystem(IconPackComponent(context.packageName)))
    val actual = requireNotNull(renderer.render(ui, 192) {})
    val expected = requireNotNull(renderer.render(host, 192) {})
    try {
      assertTrue("The split UI and service have identical native layered artwork on this fixture",
        actual.sameAs(expected))
      write("native.png", actual)
    } finally { actual.recycle(); expected.recycle() }
  }

  @Test fun everyBuiltinColorOsStyleExportsTheNativeFallbackWithoutAWhitePlate() = runBlocking {
    val packs = IconPackRepository(context).listBuiltins().packs.filter { it.colorOsProfile != null }
    assertTrue(packs.isNotEmpty())
    val expected = requireNotNull(SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)
      .render(ui, 192) {})
    try {
      for (pack in packs) for (dark in listOf(false, true)) {
        assertFalse("This target must exercise fallback", pack.entries.any { it.component?.packageName == ui.packageName })
        val result = IconPackExporter(context, IconPackLimits()).export(pack, listOf(ui), dark, 192,
          systemFallback = SystemIconFallback.DEFAULT, installedOnly = true) {}
        try {
          ZipFile(result.archive).use { zip ->
            val entry = requireNotNull(zip.getEntry("res/drawable-xxhdpi/${ui.packageName}.png"))
            val actual = zip.getInputStream(entry).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            try {
              assertTrue("${pack.builtinKey}, dark=$dark must keep full native artwork",
                actual.sameAs(expected))
              write("${pack.builtinKey}-${if (dark) "night" else "day"}.png", actual)
            } finally { actual.recycle() }
          }
          assertNotNull(result.exportedTargets.find { it.packageName == ui.packageName })
        } finally { result.archive.delete() }
      }
    } finally { expected.recycle() }
  }

  private fun write(name: String, bitmap: Bitmap) {
    val directory = File(context.cacheDir, "download-fallback-qa").apply { mkdirs() }
    File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
  }
}
