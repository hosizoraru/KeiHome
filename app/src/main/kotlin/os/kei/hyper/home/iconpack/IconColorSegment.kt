package os.kei.hyper.home.iconpack

import kotlin.math.abs

/** The RGB line between the plate and opaque ink of a baked two-color icon. */
internal class IconColorSegment(val background: Int, val foreground: Int) {
  private val axis = intArrayOf(16, 8, 0).maxBy { abs(channel(foreground, it) - channel(background, it)) }
  private val delta = channel(foreground, axis) - channel(background, axis)
  val contrast: Int get() = abs(delta)

  fun coverage(pixel: Int, tolerance: Double = 2.0): Double? {
    val rgb = pixel and 0xffffff
    if (rgb == background) return 0.0
    if (rgb == foreground) return 255.0
    if (delta == 0) return null
    val fraction = ((channel(pixel, axis) - channel(background, axis)).toDouble() / delta).coerceIn(0.0, 1.0)
    for (shift in 16 downTo 0 step 8) {
      val expected = channel(background, shift) +
        fraction * (channel(foreground, shift) - channel(background, shift))
      if (abs(channel(pixel, shift) - expected) > tolerance) return null
    }
    return fraction * 255.0
  }

  private fun channel(color: Int, shift: Int): Int = color ushr shift and 255
}
