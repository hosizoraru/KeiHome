package os.kei.hyper.home.iconpack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.perfect.PerfectIconRepository
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises an already-downloaded source; deliberately performs no network or theme application. */
@RunWith(AndroidJUnit4::class)
class PerfectSystemFallbackDeviceTest {
  @Test fun downloadedFallbackRendersAndExportsWithVerifiableSourceProvenance() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val catalog = PerfectIconRepository(context).catalog()
    assumeNotNull(catalog)
    assumeTrue("Requires an existing HyperOS4 download", "HyperOS4" in catalog!!.downloadedStyles)
    val style = SystemIconFallback.perfect("HyperOS4")
    val target = IconPackComponent("com.android.settings")
    SystemIconFallbackRenderer(context, style).use { renderer ->
    assertEquals(catalog.sourceHash, renderer.perfectSourceHash)
    val bitmap = requireNotNull(renderer.render(target, 96) {})
    try {
      assertEquals(96, bitmap.width)
      assertTrue((0 until 96).any { bitmap.getPixel(48, it) ushr 24 != 0 })
    } finally { bitmap.recycle() }
    assertNull(renderer.render(IconPackComponent("os.kei.not.in.perfect.fixture"), 96) {})
    assertNull(renderer.render(IconPackComponent("com.android.deskclock"), 96) {})
    }
    val info = context.packageManager.getPackageInfo("com.donnnno.arcticons.daynight", 0)
    val pack = IconPackDescriptor("installed:${info.packageName}", "Perfect fallback test",
      IconPackFormat.INSTALLED_APK, IconPackSource.Installed(info.packageName, info.longVersionCode,
        info.lastUpdateTime, info.versionName), emptyList())
    val result = IconPackExporter(context, IconPackLimits()).export(pack, listOf(target), false, 96, style,
      installedOnly = true) {}
    try {
      ZipFile(result.archive).use { zip ->
        val bytes = zip.getInputStream(requireNotNull(zip.getEntry(SystemIconFallbackManifest.PATH))).readBytes()
        val manifest = SystemIconFallbackManifest.decode(bytes)
        assertEquals(style, manifest.style)
        assertEquals(catalog.sourceHash, manifest.perfectSource)
        assertTrue(target.packageName in manifest.names)
        assertNotNull(zip.getEntry("res/drawable-xxhdpi/${target.packageName}.png"))
      }
    } finally { result.archive.delete() }
  }
}
