package os.kei.hyper.home.xposed

import android.content.res.Configuration
import kotlin.math.pow

internal data class IconColors(val foreground: Int, val background: Int)

/** Selects complete, opaque themed-icon pairs without synthesizing missing tones. */
internal object MonetColorPolicy {
  private const val MIN_CONTRAST = 4.5

  fun mayReplace(enabled: Boolean, current: Int?, lastAutomatic: Int?): Boolean =
    enabled && (
      current == null ||
        lastAutomatic != null && current == lastAutomatic
      )

  /** A malformed stored selection is still a selection, never an unset value. */
  fun mayReplaceRaw(enabled: Boolean, current: String?, lastAutomatic: String?): Boolean {
    val parsed = parseColor(current)
    return (current == null || parsed != null) &&
      mayReplace(enabled, parsed, parseColor(lastAutomatic))
  }

  /** Accept both signed Settings int32 values and unsigned decimal ARGB values. */
  fun parseColor(value: String?): Int? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val digits = if (text.first() == '-' || text.first() == '+') text.drop(1) else text
    if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
    val number = text.toLongOrNull() ?: return null
    return number.takeIf {
      it >= Int.MIN_VALUE.toLong() && it <= UInt.MAX_VALUE.toLong()
    }?.toInt()
  }

  /**
   * AOSP android-16.0.0_r1 iconloaderlib themed-icon resources:
   * Light: background system_accent1_100 (primary90), foreground system_accent1_700 (primary30).
   * Dark: background system_accent2_800 (secondary20), foreground system_accent1_200 (primary80).
   * https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-16.0.0_r1/iconloaderlib/res/values-v31/colors.xml
   * https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-16.0.0_r1/iconloaderlib/res/values-night-v31/colors.xml
   * Native must acknowledge both tints before the caller persists the automatic selection.
   */
  fun selectPair(
    palette: Map<String, Int>,
    uiMode: Int = Configuration.UI_MODE_NIGHT_NO,
  ): IconColors? {
    val dark = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = palette[if (dark) "secondary20" else "primary90"] ?: return null
    val foreground = palette[if (dark) "primary80" else "primary30"] ?: return null
    return if (isLegiblePair(foreground, background)) {
      IconColors(foreground = foreground, background = background)
    } else {
      null
    }
  }

  fun isLegiblePair(foreground: Int, background: Int): Boolean {
    if (foreground ushr 24 != 0xff || background ushr 24 != 0xff) return false
    val foregroundLuminance = luminance(foreground)
    val backgroundLuminance = luminance(background)
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
      (minOf(foregroundLuminance, backgroundLuminance) + 0.05) >= MIN_CONTRAST
  }

  private fun luminance(color: Int): Double {
    fun linear(component: Int): Double {
      val value = component / 255.0
      return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color ushr 16 and 0xff) +
      0.7152 * linear(color ushr 8 and 0xff) +
      0.0722 * linear(color and 0xff)
  }
}
