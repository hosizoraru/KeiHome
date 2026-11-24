package os.kei.hyper.home.iconpack

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/** Layer-specific color filters for authored adaptive artwork. */
internal enum class ExtraAdaptiveArtwork(
  background: (() -> ColorMatrixColorFilter)? = null,
  foreground: (() -> ColorMatrixColorFilter)? = null,
) {
  KEY_ATTESTATION(background = {
    recolorBackground(0xff1765a4.toInt(), 0xff80d8ff.toInt(), 0xff243a4d.toInt(), 0xff9bd9f4.toInt())
  }),
  VPN_HOTSPOT(background = {
    recolorBackground(0xff388e3c.toInt(), 0xff66bb6a.toInt(), 0xff203a30.toInt(), 0xff7ea78c.toInt())
  }, foreground = {
    ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.65f) })
  }),
  FILEYAN(foreground = { brightenFileyanRing() });

  val backgroundFilter by lazy { background?.invoke() }
  val foregroundFilter by lazy { foreground?.invoke() }
}

private const val RED_WEIGHT = 0.213f
private const val GREEN_WEIGHT = 0.715f
private const val BLUE_WEIGHT = 0.072f

private fun channels(color: Int) = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))
private fun luma(color: Int) =
  RED_WEIGHT * Color.red(color) + GREEN_WEIGHT * Color.green(color) + BLUE_WEIGHT * Color.blue(color)

// Map the plate and mark together so their edges and shading stay continuous.
private fun recolorBackground(sourcePlate: Int, sourceMark: Int, darkPlate: Int, darkMark: Int): ColorMatrixColorFilter {
  val plate = luma(sourcePlate)
  val range = luma(sourceMark) - plate
  val matrix = FloatArray(20)
  val darkPlateChannels = channels(darkPlate)
  val darkMarkChannels = channels(darkMark)
  for (index in darkPlateChannels.indices) {
    val slope = (darkMarkChannels[index] - darkPlateChannels[index]) / range
    val row = index * 5
    matrix[row] = slope * RED_WEIGHT
    matrix[row + 1] = slope * GREEN_WEIGHT
    matrix[row + 2] = slope * BLUE_WEIGHT
    matrix[row + 4] = darkPlateChannels[index] - slope * plate
  }
  matrix[18] = 1f
  return ColorMatrixColorFilter(matrix)
}

private fun brightenFileyanRing(): ColorMatrixColorFilter {
  val sourceGray = 58f
  val target = channels(0xff92a9b4.toInt())
  val matrix = ColorMatrix().array
  for (index in target.indices) {
    // 2R − G weighs the neutral ring while keeping the green center and black shadows close to the source.
    val lift = (target[index] - sourceGray) / sourceGray
    val row = index * 5
    matrix[row] += 2 * lift
    matrix[row + 1] -= lift
  }
  return ColorMatrixColorFilter(matrix)
}
