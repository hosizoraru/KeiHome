package os.kei.hyper.home.data

import kotlin.math.roundToInt

internal object MonetLaunchSurface {
  fun surfaceColor(palette: Long, declared: Int): Int {
    if (palette ushr 48 != 1L) return declared
    val luminance = (declared ushr 16 and 255) * 299 +
      (declared ushr 8 and 255) * 587 + (declared and 255) * 114
    val dark = luminance < 128_000
    val target = if (dark) 0 else 255
    val mix = if (dark) 0.65f else 0.88f
    fun channel(shift: Int) =
      (((palette ushr shift).toInt() and 255) * (1f - mix) + target * mix).roundToInt()
    return -0x1000000 or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }
}
