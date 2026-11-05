package os.kei.hyper.home.xposed

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.RoundedCorner
import kotlin.math.roundToInt

/** Public display metadata, converted to the same logical dp as Flutter. */
internal object DockScreenCorners {
  fun readQ8(context: Context): Int = runCatching {
    val display = runCatching { context.display }.getOrNull()
      ?: context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
    val density = context.createDisplayContext(display).resources.displayMetrics.density
    val radius = listOfNotNull(
      display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius,
      display.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius,
    ).maxOrNull() ?: return 0
    quantize(radius, density)
  }.getOrDefault(0)

  fun quantize(radiusPx: Int, density: Float): Int {
    if (radiusPx <= 0 || !density.isFinite() || density <= 0f) return 0
    val dp = radiusPx.toDouble() / density
    return if (dp.isFinite() && dp <= 256.0) (dp * 256.0).roundToInt() else 0
  }
}
