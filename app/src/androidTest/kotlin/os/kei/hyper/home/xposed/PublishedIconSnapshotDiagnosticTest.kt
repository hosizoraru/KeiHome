package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipFile

/** Opt-in check of a supplied, read-only copy of the actually published theme. */
class PublishedIconSnapshotDiagnosticTest {
  @Test fun publishedCalendarKeepsAppearanceDateAndFinalShape() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val input = File(context.cacheDir, "icon-chain-published.zip")
    assumeTrue("Pass -e publishedIcons true and supply the owned fixture", input.isFile &&
      InstrumentationRegistry.getArguments().getString("publishedIcons") == "true")
    val output = File(context.cacheDir, "icon-chain-render").apply { mkdirs() }
    val date = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 12, 10, 55, 0) }.timeInMillis
    ZipFile(input).use { zip ->
      val appearances = listOf(false, true).map { dark ->
        val first = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, "com.android.calendar",
          dark, Locale.SIMPLIFIED_CHINESE, date))
        try {
          val next = requireNotNull(SurfaceIconSnapshotRenderer.render(zip, "com.android.calendar",
            dark, Locale.SIMPLIFIED_CHINESE, date + 86_400_000))
          try { assertFalse("A live calendar date must not be a frozen quiet PNG", first.sameAs(next)) }
          finally { next.recycle() }
          assertEquals(0, Color.alpha(first.getPixel(0, 0)))
          File(output, "calendar-${if (dark) "night" else "day"}.png").outputStream().use {
            assertTrue(first.compress(Bitmap.CompressFormat.PNG, 100, it))
          }
          first.getPixel(20, 96)
        } finally { first.recycle() }
      }
      fun lightness(pixel: Int) = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
      assertTrue("Published night artwork must use its night background",
        lightness(appearances[1]) < lightness(appearances[0]))
    }
  }
}
