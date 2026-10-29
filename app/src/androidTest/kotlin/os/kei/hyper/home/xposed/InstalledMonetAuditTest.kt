package os.kei.hyper.home.xposed

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in read-only evidence for installed resources; no desktop/layout mutation. */
class InstalledMonetAuditTest {
  @Test
  fun reportRequestedApplicationMasks() {
    val names = InstrumentationRegistry.getArguments().getString("monet.audit.packages")
    assumeTrue("Pass monet.audit.packages to audit installed resources", !names.isNullOrBlank())
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val pm = context.packageManager
    for (name in names!!.split(',')) {
      val app = pm.getApplicationInfo(name, PackageManager.ApplicationInfoFlags.of(0))
      val info = ActivityInfo().apply { packageName = name; applicationInfo = app; icon = app.icon }
      val status = IconResourceInspector(pm).assessLauncherMask(listOf(info), context.resources.configuration)
      val resources = pm.getResourcesForApplication(app)
      val icon = resources.getDrawable(app.icon, null)
      val mask = (icon as? AdaptiveIconDrawable)?.monochrome
      val report = buildString {
        append("$name: status=$status icon=${icon.javaClass.simpleName}")
        append(" mask=${mask?.javaClass?.simpleName} themed=${mask?.canApplyTheme()}")
        if (mask != null) {
          val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
          try {
            mask.setBounds(0, 0, 96, 96)
            mask.draw(Canvas(bitmap))
            val pixels = IntArray(96 * 96)
            bitmap.getPixels(pixels, 0, 96, 0, 0, 96, 96)
            val center = (24 until 72).flatMap { y -> (24 until 72).map { x -> pixels[y * 96 + x] } }
            append(" alpha=${pixels.minOf { it ushr 24 }}..${pixels.maxOf { it ushr 24 }}")
            append(" opaque=${pixels.count { it ushr 24 == 255 }}/${pixels.size}")
            append(" centerAlpha=${center.minOf { it ushr 24 }}..${center.maxOf { it ushr 24 }}")
            append(" rgbColors=${pixels.filter { it ushr 24 == 255 }.map { it and 0xffffff }.distinct().size}")
            val visible = pixels.indices.filter { pixels[it] ushr 24 > 8 }
            if (visible.isNotEmpty()) {
              val left = visible.minOf { it % 96 }; val right = visible.maxOf { it % 96 }
              val top = visible.minOf { it / 96 }; val bottom = visible.maxOf { it / 96 }
              val inner = (top + (bottom - top) / 4..bottom - (bottom - top) / 4).flatMap { y ->
                (left + (right - left) / 4..right - (right - left) / 4).map { x -> pixels[y * 96 + x] }
              }
              append(" bounds=$left,$top..$right,$bottom innerAlpha=${inner.minOf { it ushr 24 }}")
            }
          } finally { bitmap.recycle() }
        }
      }
      instrumentation.sendStatus(0, Bundle().apply { putString("monet.audit", report) })
    }
  }
}
