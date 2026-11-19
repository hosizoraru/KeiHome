package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FooterSamplingDeviceTest {
  @Test fun renderedFooterChangesFromLightToDarkAndReleasesItsLayer() {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      val applied = LinkedBlockingQueue<Unit>()
      val id = View.generateViewId()
      lateinit var sampling: BilibiliFooterSampling
      lateinit var footer: View
      lateinit var observer: ViewTreeObserver.OnPreDrawListener
      scenario.onActivity { activity ->
        val window = activity.window
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        val decor = window.decorView
        val content = FrameLayout(activity).apply { setBackgroundColor(Color.MAGENTA) }
        activity.setContentView(content)
        footer = View(activity).apply { this.id = id; setBackgroundColor(Color.WHITE) }
        val insets = decor.rootWindowInsets
        assumeTrue(insets != null && insets.getInsets(WindowInsets.Type.tappableElement()).bottom == 0)
        val inset = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        assumeTrue(inset > 0)
        content.addView(footer, FrameLayout.LayoutParams(-1, inset * 3, Gravity.BOTTOM).apply { bottomMargin = inset })
        sampling = BilibiliFooterSampling(activity, listOf(id)) { applied.offer(Unit) }
        observer = ViewTreeObserver.OnPreDrawListener { sampling.update(decor.height - inset, null); true }
        decor.viewTreeObserver.addOnPreDrawListener(observer)
      }
      try {
        for (color in listOf(Color.WHITE, Color.BLACK)) {
          applied.clear()
          scenario.onActivity { footer.setBackgroundColor(color); it.window.decorView.invalidate() }
          assertNotNull("Footer was not sampled", applied.poll(8, TimeUnit.SECONDS))
          scenario.onActivity { activity ->
            val appearance = activity.window.insetsController!!.systemBarsAppearance
            assertEquals(color == Color.WHITE, appearance and APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
          }
          assertEquals(color, bottomPixel(scenario))
        }
      } finally {
        scenario.onActivity { it.window.decorView.viewTreeObserver.removeOnPreDrawListener(observer); sampling.clear() }
      }
      assertEquals(Color.MAGENTA, bottomPixel(scenario))
    }
  }

  private fun bottomPixel(scenario: ActivityScenario<ComponentActivity>): Int {
    val result = LinkedBlockingQueue<Int>()
    scenario.onActivity { activity ->
      val decor = activity.window.decorView
      val region = Rect(8, decor.height - 8, 9, decor.height - 7)
      val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
      decor.viewTreeObserver.registerFrameCommitCallback {
        PixelCopy.request(activity.window, region, bitmap, { status ->
          result.offer(if (status == PixelCopy.SUCCESS) bitmap.getPixel(0, 0) else Color.TRANSPARENT)
          bitmap.recycle()
        }, Handler(Looper.getMainLooper()))
      }
      decor.invalidate()
    }
    return requireNotNull(result.poll(5, TimeUnit.SECONDS))
  }
}
