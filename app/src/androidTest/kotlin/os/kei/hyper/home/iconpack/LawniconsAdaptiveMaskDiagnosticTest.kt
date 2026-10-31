package os.kei.hyper.home.iconpack

import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in check of the installed APK and exporter; never applies a pack or changes system config. */
@RunWith(AndroidJUnit4::class)
class LawniconsAdaptiveMaskDiagnosticTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val context = instrumentation.targetContext

  @Test
  fun logAdaptiveMaskAndExportCorners() {
    assumeTrue("Select this diagnostic method explicitly", InstrumentationRegistry.getArguments()
      .getString("class") == "$TEST_CLASS#logAdaptiveMaskAndExportCorners")
    val output = File(context.cacheDir, "lawnicons-mask-diagnostic")
    check(output.isDirectory || output.mkdirs())
    val report = File(output, "report.txt").apply { writeText("") }
    fun record(value: String) {
      report.appendText("$value\n")
      Log.i(TAG, value)
      instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
    }

    record("package=${context.packageName} config=${context.resources.configuration} output=$output")
    logConfiguredMask("system", Resources.getSystem(), ::record)
    logConfiguredMask("application", context.applicationContext.resources, ::record)
    val nativeArchive = File("/product/media/theme/default/icons")
    val nativeHash = if (nativeArchive.isFile && nativeArchive.canRead()) sha256(nativeArchive) else null
    val nativeMask = NativeAdaptiveIconMask.load()
    record("native archive=$nativeArchive sha256=$nativeHash recognized=${nativeMask != null}")
    if (nativeHash == NATIVE_ICONS_SHA256) {
      assertNotNull("The verified stock ConfigIconMask must load", nativeMask)
    }
    nativeMask?.let { record("native source=${it.sourcePath} ConfigIconMask=${it.pathData}") }
    assertMaskEligibility(nativeMask)
    val info = context.packageManager.getPackageInfo("app.lawnchair.lawnicons", PackageManager.PackageInfoFlags.of(0))
    val application = checkNotNull(info.applicationInfo)
    val apk = File(application.sourceDir)
    assertEquals(APK_SHA256, sha256(apk))
    val android = AndroidIconPackResources(context)
    assertEquals("app.lawnchair.lawnicons", info.packageName)
    assertEquals("2.19.0", info.versionName)
    record("apk=$apk sha256=$APK_SHA256 version=${info.versionName}/${info.longVersionCode}")
    val index = checkNotNull(android.index(info))
    val targets = listOf(
      IconPackComponent("com.android.settings", "com.android.settings.MainSettings"),
      IconPackComponent("com.miui.gallery", "com.miui.gallery.MainActivity"),
    )
    val entries = targets.map { target -> index.entries.single { it.component == target } }
    val pack = IconPackDescriptor("installed:${info.packageName}", index.displayName, IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed(info.packageName, info.longVersionCode, info.lastUpdateTime), entries)
    for (dark in listOf(false, true)) {
      val mode = if (dark) "night" else "day"
      val clippedTargets = mutableSetOf<IconPackComponent>()
      android.installed(application, dark).use { session ->
        logConfiguredMask("$mode-pack", session.resources, ::record)
        for (entry in entries) {
          val artwork = entry.artwork(dark) as IconPackArtwork.Resource
          val id = session.drawableId(artwork.drawable)
          val drawable = session.resources.getDrawable(id, null).mutate()
          val label = "$mode/${entry.component!!.packageName}"
          record("$label resource=${session.resources.getResourceName(id)} class=${drawable.javaClass.name}")
          assertTrue("$label must remain a real adaptive drawable", drawable is AdaptiveIconDrawable)
          val adaptive = drawable as AdaptiveIconDrawable
          logMask("$label/unbounded-mask", Path(adaptive.iconMask), 100, ::record)
          adaptive.setBounds(0, 0, SIZE, SIZE)
          record("$label bounds=${adaptive.bounds} foreground=${adaptive.foreground?.bounds} " +
            "background=${adaptive.background?.bounds} monochrome=${adaptive.monochrome?.bounds}")
          val currentMask = Path(adaptive.iconMask)
          logMask("$label/bounded-mask", currentMask, SIZE, ::record)
          val rectangular = NativeAdaptiveIconMask.isRectangle(currentMask, adaptive.bounds)
          val clip = nativeMask?.pathFor(adaptive)
          record("$label exactRectangle=$rectangular nativeClip=${clip != null}")
          if (rectangular) {
            val region = Region().apply { setPath(currentMask, Region(adaptive.bounds)) }
            for ((x, y) in samplePoints(SIZE)) {
              assertTrue("$label rectangle sample $x,$y", region.contains(x, y))
            }
          }
          if (nativeMask != null && rectangular) {
            assertNotNull("$label must use the recognized native mask", clip)
            logMask("$label/native-clip", checkNotNull(clip), SIZE, ::record)
            val region = Region().apply { setPath(clip, Region(adaptive.bounds)) }
            for ((x, y) in cornerPoints(SIZE)) {
              assertFalse("$label native mask must exclude corner $x,$y", region.contains(x, y))
            }
            assertTrue("$label native mask must retain its center", region.contains(SIZE / 2, SIZE / 2))
            clippedTargets += entry.component
          } else {
            assertNull("$label must keep its current mask", clip)
          }
        }
      }
      val exported = IconPackExporter(context, IconPackLimits()).export(pack, targets, dark, SIZE) {}
      try {
        assertEquals(targets.toSet(), exported.exportedTargets.toSet())
        record("$mode exportSha256=${exported.sha256} skipped=${exported.skippedTargets}")
        ZipFile(exported.archive).use { zip ->
          for (target in targets) {
            val name = "res/drawable-xxhdpi/${target.packageName}.png"
            val bytes = zip.getInputStream(checkNotNull(zip.getEntry(name))).use { it.readBytes() }
            val png = File(output, "$mode-${target.packageName}.png").apply { writeBytes(bytes) }
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
              assertEquals(SIZE, bitmap.width)
              assertEquals(SIZE, bitmap.height)
              record("$mode/${target.packageName} png=$png ${alphaSummary(bitmap)}")
              if (target in clippedTargets) {
                for ((x, y) in cornerPoints(SIZE)) {
                  assertEquals("$mode/$target exported corner $x,$y must be transparent",
                    0, bitmap.getPixel(x, y) ushr 24)
                }
                assertEquals("$mode/$target must retain the opaque adaptive background",
                  255, bitmap.getPixel(SIZE / 2, SIZE / 2) ushr 24)
              }
            } finally { bitmap.recycle() }
          }
        }
      } finally { exported.archive.delete() }
    }
  }

  private fun assertMaskEligibility(nativeMask: NativeAdaptiveIconMask?) {
    val bounds = Rect(0, 0, 100, 100)
    // Match config_icon_mask's extra collinear start point, not just Path.addRect().
    val square = Path().apply {
      moveTo(50f, 0f); lineTo(100f, 0f); lineTo(100f, 100f)
      lineTo(0f, 100f); lineTo(0f, 0f); close()
    }
    assertTrue("The framework square must be recognized", NativeAdaptiveIconMask.isRectangle(square, bounds))
    val shaped = Path().apply { addRoundRect(RectF(bounds), 20f, 20f, Path.Direction.CW) }
    assertFalse("A shaped mask must be preserved", NativeAdaptiveIconMask.isRectangle(shaped, bounds))
    val holed = Path(square).apply {
      fillType = Path.FillType.EVEN_ODD
      addCircle(50f, 50f, 8f, Path.Direction.CW)
    }
    assertFalse("Bounds and corner samples cannot prove a rectangle",
      NativeAdaptiveIconMask.isRectangle(holed, bounds))
    nativeMask?.let {
      val ordinary = ColorDrawable(Color.BLACK).apply { setBounds(bounds) }
      assertNull("Ordinary drawables must never receive the native clip", it.pathFor(ordinary))
      val adaptive = object : AdaptiveIconDrawable(ColorDrawable(Color.BLACK), ColorDrawable(Color.WHITE)) {
        override fun getIconMask(): Path = Path(shaped)
      }.apply { setBounds(bounds) }
      assertNull("Existing adaptive shapes must never receive the native clip", it.pathFor(adaptive))
    }
  }

  private fun logConfiguredMask(label: String, resources: Resources, record: (String) -> Unit) {
    val id = resources.getIdentifier("config_icon_mask", "string", "android")
    val value = if (id == 0) "<not found>" else resources.getString(id)
    record("$label config_icon_mask id=0x${id.toString(16)} value=$value")
  }

  private fun logMask(label: String, path: Path, size: Int, record: (String) -> Unit) {
    val bounds = RectF()
    path.computeBounds(bounds)
    val region = Region().apply { setPath(path, Region(0, 0, size, size)) }
    val probes = samplePoints(size).joinToString { (x, y) -> "$x,$y:${region.contains(x, y)}" }
    val measure = PathMeasure(path, false)
    val samples = (0..16).joinToString {
      val xy = FloatArray(2)
      if (measure.getPosTan(measure.length * it / 16f, xy, null)) {
        String.format(Locale.ROOT, "%.2f,%.2f", xy[0], xy[1])
      } else "<empty>"
    }
    record("$label bounds=$bounds empty=${path.isEmpty} fill=${path.fillType} isRect=${path.isRect(null)} " +
      "length=${measure.length} probes=[$probes] contourSamples=[$samples]")
  }

  private fun alphaSummary(bitmap: Bitmap): String {
    var transparent = 0
    var opaque = 0
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
      bitmap.getPixels(row, 0, row.size, 0, y, row.size, 1)
      for (pixel in row) when (pixel ushr 24) {
        0 -> transparent++
        255 -> opaque++
      }
    }
    val probes = samplePoints(bitmap.width).joinToString { (x, y) ->
      "$x,$y:${bitmap.getPixel(x, y) ushr 24}"
    }
    return "alpha=[$probes] transparent=$transparent opaque=$opaque " +
      "partial=${bitmap.width * bitmap.height - transparent - opaque}"
  }

  private fun cornerPoints(size: Int) = listOf(
    0 to 0, (size - 1) to 0, 0 to (size - 1), (size - 1) to (size - 1),
  )

  private fun samplePoints(size: Int) = cornerPoints(size) + listOf(
    (size / 20) to (size / 20), (size - 1 - size / 20) to (size / 20),
    (size / 2) to 0, 0 to (size / 2), (size / 2) to (size / 2),
  )

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  private companion object {
    const val TAG = "KeiHomeLawnMask"
    const val SIZE = 192
    const val APK_SHA256 = "5ed67a0338c207bd0f8c44392dedbae8d0449fef5c8d773a202451d42bbd8cdd"
    const val NATIVE_ICONS_SHA256 = "31375bb5949b0b2510f1ba12b9fac03bdf3a82cc1ea4fb8255d8f552b5048210"
    const val TEST_CLASS = "os.kei.hyper.home.iconpack.LawniconsAdaptiveMaskDiagnosticTest"
  }
}
