package os.kei.hyper.home.iconpack

import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable

/** Layer bounds belong to the framework; the caller still owns the final icon mask. */
internal object NativeLayerViewport {
  fun bounds(output: Rect): Rect {
    val child = ColorDrawable()
    AdaptiveIconDrawable(child, null).bounds = output
    return Rect(child.bounds)
  }
}
