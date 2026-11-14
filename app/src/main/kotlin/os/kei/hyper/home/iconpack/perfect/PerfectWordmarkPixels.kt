package os.kei.hyper.home.iconpack.perfect

/** Dark wordmarks, including warm ink and underlines touching colored illustrations. */
internal object PerfectWordmarkPixels {
  private data class Part(
    val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val area: Int, val touchesColor: Boolean, val neutralCore: Boolean,
  ) {
    val width get() = right - left + 1
    val height get() = bottom - top + 1
  }

  private fun ink(p: Int, fringe: Boolean = false): Boolean {
    val a = p ushr 24
    if (a == 0) return false
    val hi = maxOf(p ushr 16 and 255, p ushr 8 and 255, p and 255)
    val lo = minOf(p ushr 16 and 255, p ushr 8 and 255, p and 255)
    val rounding = if (fringe) (254 + a) / a else 0
    return hi <= 112 + rounding && hi - lo <= 64 + rounding
  }

  fun lift(
    pixels: IntArray, underlay: IntArray, width: Int, height: Int, check: () -> Unit,
  ): Boolean {
    if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong() ||
      underlay.size != pixels.size || pixels.size > 4 * 1024 * 1024) return false
    val labels = IntArray(pixels.size)
    val queue = IntArray(pixels.size)
    val parts = ArrayList<Part>()
    var components = 0
    for (start in pixels.indices) {
      if (start % 1024 == 0) check()
      if (labels[start] != 0 || pixels[start] ushr 24 < 160 || !ink(pixels[start])) continue
      if (++components > 4096) return false
      val id = components
      var head = 0
      var tail = 1
      var left = width
      var top = height
      var right = 0
      var bottom = 0
      var opaque = 0
      var neutral = 0
      var dark = 0
      var touchesColor = false
      queue[0] = start
      labels[start] = id
      fun visit(i: Int) {
        if (pixels[i] ushr 24 < 160) return
        if (!ink(pixels[i])) { touchesColor = true; return }
        if (labels[i] == 0) { labels[i] = id; queue[tail++] = i }
      }
      while (head < tail) {
        if (head % 1024 == 0) check()
        val i = queue[head++]
        val x = i % width
        val y = i / width
        left = minOf(left, x); right = maxOf(right, x)
        top = minOf(top, y); bottom = maxOf(bottom, y)
        val p = pixels[i]
        if (p ushr 24 >= 240) opaque++
        val hi = maxOf(p ushr 16 and 255, p ushr 8 and 255, p and 255)
        val lo = minOf(p ushr 16 and 255, p ushr 8 and 255, p and 255)
        if (hi <= 96 && hi - lo <= 12) neutral++
        val b = underlay[i]
        val luma = ((b ushr 16 and 255) * 2126 +
          (b ushr 8 and 255) * 7152 + (b and 255) * 722) / 10000
        if (b ushr 24 >= 128 && luma <= 96) dark++
        if (x > 0) visit(i - 1)
        if (x + 1 < width) visit(i + 1)
        if (y > 0) visit(i - width)
        if (y + 1 < height) visit(i + width)
      }
      if (tail >= maxOf(8, pixels.size / 6000) && opaque * 10L >= tail * 7L &&
        dark * 20L >= tail * 19L &&
        left > 0 && top > 0 && right < width - 1 && bottom < height - 1)
        parts.add(Part(id, left, top, right, bottom, tail, touchesColor, neutral * 5L >= tail))
    }
    // Bound pairwise work and reject texture-like sets. Colored eyes cannot anchor
    // a row: anchor ink must also be separate from the colored opaque artwork.
    if (parts.size !in 2..128) return false
    val unit = minOf(width, height)
    val bands = IntArray(components + 1)
    val sizes = IntArray(bands.size)
    var band = 0
    // Preserve the original large-letter proof before admitting smaller text rows.
    for (smallRow in listOf(false, true)) {
      val anchors = parts.filter {
        val minimum = unit / if (smallRow) 24 else 12
        !it.touchesColor && it.neutralCore && it.width >= minimum && it.height >= minimum &&
          it.area * 50L <= it.width.toLong() * it.height * 39
      }
      val parents = IntArray(anchors.size) { it }
      fun root(i: Int): Int {
        var n = i
        while (parents[n] != n) n = parents[n]
        return n
      }
      for (i in anchors.indices) {
        check()
        val a = anchors[i]
        for (j in 0 until i) {
          val b = anchors[j]
          val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) + 1
          val gap = maxOf(a.left, b.left) - minOf(a.right, b.right) - 1
          val centreDistance = kotlin.math.abs(a.top + a.bottom - b.top - b.bottom)
          if (overlap * 2 >= minOf(a.height, b.height) &&
            centreDistance * 3 <= maxOf(a.height, b.height) * 2 &&
            gap * 2 <= maxOf(a.height, b.height))
            parents[root(i)] = root(j)
        }
      }
      for (group in anchors.indices.groupBy { root(it) }.values) {
        check()
        // Small lettering needs a longer aligned row to distinguish it from facial details.
        if (group.size < if (smallRow) 4 else 2) continue
        val left = group.minOf { anchors[it].left }
        val right = group.maxOf { anchors[it].right }
        val top = group.minOf { anchors[it].top }
        val bottom = group.maxOf { anchors[it].bottom }
        val rowWidth = right - left + 1
        val rowHeight = bottom - top + 1
        if (rowWidth * 3 < width || rowWidth * 4 > width * 3 ||
          rowHeight * (if (smallRow) 20 else 10) < height || rowHeight * 3 > height ||
          rowWidth * 2 < rowHeight * 3) continue
        band++
        for (part in parts) {
          // Include complete dots, stems and underlines, even when an underline
          // touches the mascot. Never clip a larger ink component into this row.
          if (bands[part.id] == 0 &&
            part.left >= left - rowHeight / 3 && part.right <= right + rowHeight / 3 &&
            part.top >= top - rowHeight / 8 && part.bottom <= bottom + rowHeight / 8) {
            bands[part.id] = band
            sizes[part.id] = part.area
          }
        }
      }
    }
    if (band == 0) return false
    val fringe = IntArray(bands.size)
    val valid = BooleanArray(bands.size) { bands[it] != 0 }
    val depth = ByteArray(pixels.size)
    var head = 0
    var tail = 0
    for (i in pixels.indices) if (labels[i] != 0) queue[tail++] = i
    val limit = (unit / 128).coerceIn(1, 4)
    while (head < tail) {
      if (head % 1024 == 0) check()
      val i = queue[head++]
      val id = labels[i]
      val x = i % width
      fun visit(j: Int) {
        val p = pixels[j]
        val a = p ushr 24
        if (a == 0) return
        if (labels[j] != 0) {
          val other = labels[j]
          if (other != id && (bands[id] == 0 || bands[id] != bands[other])) {
            valid[id] = false; valid[other] = false
          }
          return
        }
        if (depth[i] >= limit || a >= 160 || a > pixels[i] ushr 24 || !ink(p, fringe = true)) return
        labels[j] = id
        depth[j] = (depth[i] + 1).toByte()
        queue[tail++] = j
        if (a >= 32) fringe[id]++
      }
      if (x > 0) visit(i - 1)
      if (x + 1 < width) visit(i + 1)
      if (i >= width) visit(i - width)
      if (i + width < pixels.size) visit(i + width)
    }
    for (id in valid.indices) if (fringe[id] > sizes[id] * 2L) valid[id] = false
    var changed = false
    for (i in pixels.indices) {
      if (i % 1024 == 0) check()
      if (valid[labels[i]]) {
        pixels[i] = (pixels[i] and -0x1000000) or 0xffffff
        changed = true
      }
    }
    return changed
  }
}
