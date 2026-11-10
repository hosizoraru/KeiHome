package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Test

class HostAppIconPresentationDeviceTest {
  @Test fun unchangedBitmapReusesItsWrapperAndPendingRefreshKeepsItVisible() {
    val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
    val original = ColorDrawable(Color.RED)
    val bound = SidebarIconPresentation("os.kei.hyper.home", original)
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val first = bound.image(view, bitmap, false)
    repeat(30) { assertSame(first, bound.image(view, bitmap, false)) }
    assertSame(first, bound.image(view, null, true))
    assertSame(original, bound.image(view, null, false))
  }

  @Test fun nativeFallbackTracksTheLatestOriginalWithoutRetainingTheView() {
    val view = ImageView(InstrumentationRegistry.getInstrumentation().targetContext)
    val bound = SidebarIconPresentation("os.kei.hyper.home", ColorDrawable(Color.RED))
    val updated = ColorDrawable(Color.BLUE)
    bound.original = updated
    assertSame(updated, bound.image(view, null, false))
    assertSame(updated, bound.image(view, null, true))
  }
}
