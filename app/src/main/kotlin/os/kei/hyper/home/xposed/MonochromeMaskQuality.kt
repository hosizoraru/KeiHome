package os.kei.hyper.home.xposed

/** Inspect alpha, including transparent padding around incorrectly declared full-color bitmaps. */
internal object MonochromeMaskQuality {
  fun isUnusable(pixels: IntArray, width: Int, height: Int, bitmap: Boolean): Boolean {
    if (width < 1 || height < 1 || width.toLong() * height != pixels.size.toLong()) return false
    val firstAlpha = pixels.first() ushr 24
    if (pixels.all { it ushr 24 == firstAlpha }) return true
    if (!bitmap) return false

    var left = width
    var top = height
    var right = -1
    var bottom = -1
    var opaque = 0
    var minR = 255; var minG = 255; var minB = 255
    var maxR = 0; var maxG = 0; var maxB = 0
    val colors = HashSet<Int>()
    for (index in pixels.indices) {
      val color = pixels[index]
      val alpha = color ushr 24
      if (alpha <= 8) continue
      val x = index % width
      val y = index / width
      left = minOf(left, x); right = maxOf(right, x)
      top = minOf(top, y); bottom = maxOf(bottom, y)
      if (alpha < 250) continue
      opaque++
      val r = (color ushr 16) and 255
      val g = (color ushr 8) and 255
      val b = color and 255
      minR = minOf(minR, r); maxR = maxOf(maxR, r)
      minG = minOf(minG, g); maxG = maxOf(maxG, g)
      minB = minOf(minB, b); maxB = maxOf(maxB, b)
      if (colors.size < 8) colors.add(color and 0xffffff)
    }
    val w = right - left + 1
    val h = bottom - top + 1
    if (w < 8 || h < 8 || w * 4 < h * 3 || h * 4 < w * 3 ||
      opaque.toLong() * 100 < w.toLong() * h * 85 || colors.size < 8 ||
      maxOf(maxR - minR, maxG - minG, maxB - minB) < 32
    ) return false

    // A dense, colored square with no interior alpha detail loses its artwork
    // entirely under srcIn. Preserve real alpha glyphs (including eye/cutout
    // details), sparse colored artwork and single-color silhouettes.
    for (y in top + h / 4 until bottom + 1 - h / 4) {
      for (x in left + w / 4 until right + 1 - w / 4) {
        if (pixels[y * width + x] ushr 24 < 250) return false
      }
    }
    return true
  }
}
