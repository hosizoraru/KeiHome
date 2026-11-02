package os.kei.hyper.home.xposed

import com.materialkolor.utils.ColorUtils

/** ColorOS's LAB lightness reflection, restricted to opaque, nearly neutral backgrounds. */
internal object SplashBackgroundPolicy {
  fun adapt(color: Int, night: Boolean, protectedArtwork: Boolean = false): Int {
    if (!night || protectedArtwork || color ushr 24 != 255) return color
    if (!isNeutral(color)) return color
    val lab = ColorUtils.labFromArgb(color)
    if (lab[0] <= 50.0) return color
    return ColorUtils.argbFromLab(100.0 - lab[0], lab[1], lab[2])
  }

  fun isNeutral(color: Int): Boolean {
    val red = color ushr 16 and 255
    val green = color ushr 8 and 255
    val blue = color and 255
    return maxOf(red, green, blue) - minOf(red, green, blue) <= 16
  }
}
