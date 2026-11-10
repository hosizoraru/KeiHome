package os.kei.hyper.home.iconpack

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import os.kei.hyper.home.data.SystemIconFallback

/** Read-only factory metadata and private exports; no active-theme changes. */
class PreinstalledSystemFallbackDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val pm = context.packageManager
  private val candidates = listOf(
    "com.miui.themestore", "com.miui.securitymanager", "com.miui.virtualsim",
    "com.mipay.wallet", "com.xiaomi.scanner", "com.miui.voiceassistProxy", "com.xiaomi.smarthome",
    "com.android.soundrecorder", "com.android.providers.downloads.ui", "com.miui.compass",
    "com.miui.notes", "com.miui.calculator", "com.miui.screenrecorder",
  )

  @Test fun factoryAppsRemainEligibleAfterMovingIntoDataWithoutPromotingOtherUserApps() {
    assumeTrue(File("/product/data-app").isDirectory)
    val renderer = SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)
    val rows = JSONArray()
    for (name in candidates) {
      val info = try { pm.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0)) }
        catch (_: PackageManager.NameNotFoundException) { continue }
      assertEquals("Fixture must cover a non-system preload", 0,
        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
      val start = SystemClock.elapsedRealtimeNanos()
      assertTrue(name, renderer.isSystem(IconPackComponent(name)))
      rows.put(JSONObject().put("package", name).put("source", info.sourceDir)
        .put("firstLookupMicros", (SystemClock.elapsedRealtimeNanos() - start) / 1000))
    }
    assertFalse(renderer.isSystem(IconPackComponent(context.packageName)))
    assumeTrue(rows.length() > 0)
    val directory = File(context.cacheDir, "preload-fallback-qa").apply { mkdirs() }
    directory.resolve("classification.json").writeText(rows.toString(2))
    val start = SystemClock.elapsedRealtimeNanos()
    repeat(100) {
      for (index in 0 until rows.length()) {
        assertTrue(renderer.isSystem(IconPackComponent(rows.getJSONObject(index).getString("package"))))
      }
    }
    directory.resolve("warm-lookups.json").writeText(JSONObject()
      .put("lookups", rows.length() * 100)
      .put("elapsedMicros", (SystemClock.elapsedRealtimeNanos() - start) / 1000).toString(2))
  }

  @Test fun allColorOsPacksKeepTheirMappingsAndUseFullNativeArtForMissingFactoryApps() = runBlocking {
    assumeTrue(File("/product/data-app").isDirectory)
    val packs = IconPackRepository(context).listBuiltins().packs.filter { it.colorOsProfile != null }
    assertTrue(packs.isNotEmpty())
    val targets = candidates.filter {
      try { pm.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0)); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    }.map(::IconPackComponent)
    assumeTrue(targets.isNotEmpty())
    val directory = File(context.cacheDir, "preload-fallback-qa").apply { mkdirs() }
    val renderer = SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)
    val counts = JSONArray()
    for (pack in packs) for (dark in listOf(false, true)) {
      val result = IconPackExporter(context, IconPackLimits()).export(pack, targets, dark, 192,
        systemFallback = SystemIconFallback.DEFAULT, installedOnly = true) {}
      try {
        var nativeCount = 0
        var mappedCount = 0
        val details = JSONArray()
        ZipFile(result.archive).use { zip ->
          for (target in targets) {
            val path = "res/drawable-xxhdpi/${target.packageName}.png"
            val entry = zip.getEntry(path)
            assertNotNull("${pack.builtinKey}, $target must remain covered", entry)
            if (pack.entries.any { it.component?.packageName == target.packageName }) {
              mappedCount++
              details.put(JSONObject().put("package", target.packageName).put("artwork", "pack"))
              continue
            }
            val expected = requireNotNull(renderer.render(target, 192) {})
            val actual = zip.getInputStream(requireNotNull(entry)).use {
              requireNotNull(BitmapFactory.decodeStream(it))
            }
            try {
              assertTrue("${pack.builtinKey}, ${target.packageName}, dark=$dark", actual.sameAs(expected))
              if (!dark && pack.builtinKey == "coloros-classic-art-on") {
                File(directory, "${target.packageName}.png").outputStream().use {
                  actual.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
              }
            } finally { expected.recycle(); actual.recycle() }
            nativeCount++
            details.put(JSONObject().put("package", target.packageName).put("artwork", "native"))
          }
        }
        counts.put(JSONObject().put("pack", pack.builtinKey).put("dark", dark)
          .put("native", nativeCount).put("mapped", mappedCount).put("targets", details))
      } finally { result.archive.delete() }
    }
    File(directory, "exports.json").writeText(counts.toString(2))
  }

  @Test fun clockAndCalendarKeepTheirDynamicOriginals() {
    val renderer = SystemIconFallbackRenderer(context, SystemIconFallback.DEFAULT)
    for (name in listOf("com.android.calendar", "com.android.deskclock")) {
      try { pm.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0)) }
        catch (_: PackageManager.NameNotFoundException) { continue }
      assertNull(name, renderer.render(IconPackComponent(name), 192) {})
    }
  }
}
