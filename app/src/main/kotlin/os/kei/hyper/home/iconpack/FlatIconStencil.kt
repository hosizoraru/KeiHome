package os.kei.hyper.home.iconpack

import kotlin.math.roundToInt

/** Recognizes a dominant flat plate and a solid mark with color-blended antialiasing. */
internal object FlatIconStencil {
  data class Proof(val colors: IconColorSegment, val ink: ByteArray, val top: Int, val insetMark: Boolean)

  fun prove(pixels: IntArray, size: Int, check: () -> Unit): Proof? {
    if (size !in 32..512 || pixels.size != size * size) return null
    val colors = HashMap<Int, Int>()
    var opaque = 0
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        val pixel = pixels[y * size + x]
        if (pixel ushr 24 < 250) continue
        opaque++
        val rgb = pixel and 0xffffff
        colors[rgb] = (colors[rgb] ?: 0) + 1
        // A two-color edge can introduce intermediate shades. Cap work on photographic inputs.
        if (colors.size > 1024) return null
      }
    }
    if (opaque * 2 < pixels.size) return null
    val plate = colors.maxByOrNull { it.value } ?: return null
    if (plate.value * 5 < opaque * 3) return null
    val mark = colors.asSequence().filter { it.key != plate.key }.maxByOrNull { it.value } ?: return null
    if (mark.value * 100 < opaque) return null
    val segment = IconColorSegment(plate.key, mark.key)
    if (segment.contrast < 96) return null

    val ink = ByteArray(pixels.size)
    val band = size / 16
    var insetMark = true
    var edgePlate = 0
    var edgeMark = 0
    var blended = 0
    var top = size
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        val i = y * size + x
        val pixel = pixels[i]
        val alpha = pixel ushr 24
        if (alpha < 32) continue
        val coverage = segment.coverage(pixel, tolerance = if (alpha >= 250) 3.0 else 8.0)
          ?.roundToInt() ?: return null
        if (coverage in 4..251 && ++blended * 8 > opaque) return null
        if (coverage >= 128) top = minOf(top, y)
        if (x < band || y < band || x >= size - band || y >= size - band) {
          if (coverage >= 4) {
            edgeMark++
            insetMark = false
          } else if (alpha >= 250) edgePlate++
        }
        ink[i] = coverage.toByte()
      }
    }
    if (top == size || edgePlate == 0 || edgeMark * 3 > edgePlate) return null
    return Proof(segment, ink, top, insetMark)
  }
}
