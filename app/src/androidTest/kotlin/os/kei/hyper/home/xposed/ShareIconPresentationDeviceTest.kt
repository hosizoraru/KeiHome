package os.kei.hyper.home.xposed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ShareIconPresentationDeviceTest {
  private class RecordingView(context: Context) : ImageView(context) {
    var writes = 0
    override fun setImageDrawable(drawable: Drawable?) {
      writes++
      super.setImageDrawable(drawable)
    }
  }

  @Test fun unchangedSnapshotsReuseDrawableAndDoNotInvalidateView() = onMain {
    val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val view = RecordingView(InstrumentationRegistry.getInstrumentation().targetContext)
    val original = ColorDrawable(Color.RED)
    val presentation = ShareIconPresentation(original)
    try {
      view.setImageDrawable(original)
      view.writes = 0
      presentation.apply(view, bitmap)
      val replacement = view.drawable
      repeat(128) { presentation.apply(view, bitmap) }
      assertEquals(1, view.writes)
      assertSame(replacement, view.drawable)

      // A native rebind can restore its own drawable; reuse the existing wrapper.
      view.setImageDrawable(original)
      presentation.apply(view, bitmap)
      assertSame(replacement, view.drawable)
      assertEquals(3, view.writes)
      assertFalse(bitmap.isRecycled)
    } finally { view.setImageDrawable(null); bitmap.recycle() }
  }

  @Test fun newAppearanceReplacesSnapshotAndMissingResourceRestoresNativeOriginal() = onMain {
    val first = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val second = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
    val original = ColorDrawable(Color.BLUE)
    val presentation = ShareIconPresentation(original)
    val view = RecordingView(InstrumentationRegistry.getInstrumentation().targetContext)
    try {
      presentation.apply(view, first)
      val before = view.drawable
      presentation.apply(view, second)
      assertNotSame(before, view.drawable)
      val replacement = view.drawable
      val pendingWrites = view.writes
      presentation.apply(view, null, pending = true)
      assertSame("Keep the last valid image while its replacement is loading", replacement, view.drawable)
      assertEquals(pendingWrites, view.writes)
      presentation.apply(view, null)
      assertSame(original, view.drawable)
      val writes = view.writes
      presentation.apply(view, null)
      assertEquals(writes, view.writes)
      assertFalse(first.isRecycled)
      assertFalse(second.isRecycled)
    } finally { view.setImageDrawable(null); first.recycle(); second.recycle() }
  }

  private fun onMain(body: () -> Unit) =
    InstrumentationRegistry.getInstrumentation().runOnMainSync(body)
}
