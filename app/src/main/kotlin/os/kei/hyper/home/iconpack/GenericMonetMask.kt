package os.kei.hyper.home.iconpack

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Generation-time contrast extraction. Alpha-only tinting would erase opaque logos. */
internal object GenericMonetMask {
  /** Input is a completed mask. Adaptive child expansion has already happened upstream. */
  fun insetFullBleed(mask: IntArray, size: Int, clipAlpha: IntArray? = null,
    check: () -> Unit = {}): IntArray {
    require(size in 1..512 && mask.size == size * size && (clipAlpha == null || clipAlpha.size == mask.size))
    if (size < 16) return mask
    // Move the visible, completed silhouette inward, including its final outer shape.
    // Minimum coverage keeps already clipped antialiased edges from being multiplied twice.
    fun alpha(index: Int): Int = min(mask[index] ushr 24, clipAlpha?.get(index) ?: 255)
    val band = max(1, size / 10)
    val middle = size / 5 until size - size / 5
    var left = 0; var right = 0; var top = 0; var bottom = 0
    var visible = 0
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        if (alpha(y * size + x) < 64) continue
        visible++
        if (y in middle) {
          if (x < band) left++
          if (x >= size - band) right++
        }
        if (x in middle) {
          if (y < band) top++
          if (y >= size - band) bottom++
        }
      }
    }
    val threshold = max(1, band * middle.count() / 6)
    // Opposite occupied edge bands distinguish full artwork from a small protruding detail.
    if (visible < mask.size / 8 ||
      !((left >= threshold && right >= threshold) || (top >= threshold && bottom >= threshold))) return mask
    val inset = (size * .20f).roundToInt()
    val inner = size - 2 * inset
    val result = IntArray(mask.size) { 0x00ffffff }
    for (y in 0 until inner) {
      check()
      val sy = ((y + .5f) * size / inner - .5f).coerceIn(0f, size - 1f)
      val y0 = sy.toInt(); val y1 = (y0 + 1).coerceAtMost(size - 1)
      val fy = sy - y0
      for (x in 0 until inner) {
        val sx = ((x + .5f) * size / inner - .5f).coerceIn(0f, size - 1f)
        val x0 = sx.toInt(); val x1 = (x0 + 1).coerceAtMost(size - 1)
        val fx = sx - x0
        val a = alpha(y0 * size + x0) * (1 - fx) + alpha(y0 * size + x1) * fx
        val b = alpha(y1 * size + x0) * (1 - fx) + alpha(y1 * size + x1) * fx
        result[(y + inset) * size + x + inset] = ((a * (1 - fy) + b * fy).roundToInt() shl 24) or 0x00ffffff
      }
    }
    return result
  }

  fun background(pixels: IntArray, size: Int, check: () -> Unit = {}): IntArray {
    require(size in 1..512 && pixels.size == size * size)
    val radius = size * 0.22f
    return IntArray(pixels.size) { i ->
      if (i % size == 0) check()
      val x = i % size + 0.5f
      val y = i / size + 0.5f
      val dx = max(radius - x, max(0f, x - (size - radius)))
      val dy = max(radius - y, max(0f, y - (size - radius)))
      val coverage = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
      (((pixels[i] ushr 24) * coverage).roundToInt() shl 24) or 0x00ffffff
    }
  }

  fun foreground(
    pixels: IntArray, size: Int, check: () -> Unit = {},
  ): IntArray? = foreground(pixels, size, plate = null, check = check)

  fun foreground(
    pixels: IntArray, size: Int, plate: IntArray?, check: () -> Unit = {},
  ): IntArray? {
    require(size in 1..512 && pixels.size == size * size && (plate == null || plate.size == pixels.size))
    FlatIconStencil.prove(pixels, size, check)?.let { stencil ->
      val effectivePlate = plate ?: background(pixels, size, check)
      return IntArray(pixels.size) { i ->
        if (i % size == 0) check()
        val alpha = ((effectivePlate[i] ushr 24) * (stencil.ink[i].toInt() and 255) + 127) / 255
        (alpha shl 24) or 0x00ffffff
      }
    }
    val border = IntArray(4096)
    var borderCount = 0
    fun bucket(c: Int) = ((c ushr 20 and 15) shl 8) or
      ((c ushr 12 and 15) shl 4) or (c ushr 4 and 15)
    var transparent = 0
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        val c = pixels[y * size + x]
        if (c ushr 24 < 32) transparent++
        if ((x < size / 8 + 1 || x >= size - size / 8 - 1 ||
            y < size / 8 + 1 || y >= size - size / 8 - 1) && c ushr 24 >= 224) {
          border[bucket(c)]++
          borderCount++
        }
      }
    }
    // A cutout with no opaque plate already supplies its own foreground silhouette.
    val cutout = borderCount == 0 && transparent > pixels.size / 4
    val dominant = border.indices.maxBy { border[it] }
    if (!cutout && borderCount == 0) return null
    val br = (dominant ushr 8 and 15) * 17
    val bg = (dominant ushr 4 and 15) * 17
    val bb = (dominant and 15) * 17
    val contrasts = IntArray(pixels.size)
    val histogram = IntArray(256)
    for (y in 0 until size) {
      check()
      for (x in 0 until size) {
        val i = y * size + x
        val c = pixels[i]
        val contrast = max(abs((c ushr 16 and 255) - br),
          max(abs((c ushr 8 and 255) - bg), abs((c and 255) - bb)))
        contrasts[i] = contrast
        if (c ushr 24 >= 32) histogram[contrast]++
      }
    }
    var remaining = histogram.sum() / 50
    var peak = 255
    while (peak > 0 && remaining >= histogram[peak]) remaining -= histogram[peak--]
    if (!cutout && peak < 24) return null // A uniform plate has no recoverable mark.
    val effectivePlate = plate ?: background(pixels, size, check)
    return IntArray(pixels.size) { i ->
      val alpha = effectivePlate[i] ushr 24
      val mask = if (cutout) alpha else
        (alpha * ((contrasts[i] - 12).toFloat() / (peak - 12)).coerceIn(0f, 1f)).roundToInt()
      (mask shl 24) or 0x00ffffff
    }
  }

  /**
   * Extracts a monochrome alpha mask from an authored foreground layer.
   *
   * Single-color / softly shaded components keep their exact authored alpha silhouette.
   * Multi-color components preserve internal brand geometry instead of collapsing into a
   * featureless silhouette blob: components with a dominant body carve high-contrast
   * interior details out of that body, while multi-color collages (e.g. overlapping petals)
   * use border-contrast masked strictly by the foreground alpha (avoiding background haze)
   * or luminance shading.
   */
  fun layeredForeground(
    foreground: IntArray, plate: IntArray, size: Int, check: () -> Unit,
  ): IntArray? = layeredForeground(foreground, composite = null, plate = plate, size = size, check = check)

  fun layeredForeground(
    foreground: IntArray,
    composite: IntArray?,
    plate: IntArray,
    size: Int,
    check: () -> Unit,
  ): IntArray? {
    require(size in 1..512 && foreground.size == size * size && plate.size == foreground.size &&
      (composite == null || composite.size == foreground.size))
    var interior = 0
    var clear = 0
    var ink = 0
    for (i in foreground.indices) {
      if (i % size == 0) check()
      val alpha = foreground[i] ushr 24
      if (plate[i] ushr 24 >= 224) {
        interior++
        if (alpha <= 8) clear++
        if (alpha >= 64) ink++
      }
    }
    if (interior == 0 || clear * 10 < interior || ink * 100 < interior) return null

    // Compute composite border contrast lazily only if a multi-color collage component needs it.
    var bgReady = false
    var bgPeak = 0
    var bgContrasts: IntArray? = null
    fun ensureBorderContrast() {
      if (bgReady) return
      bgReady = true
      val src = composite ?: return
      val border = IntArray(4096)
      var borderCount = 0
      val band = size / 8 + 1
      for (y in 0 until size) {
        check()
        for (x in 0 until size) {
          val c = src[y * size + x]
          if ((x < band || x >= size - band || y < band || y >= size - band) && c ushr 24 >= 224) {
            val b = ((c ushr 20 and 15) shl 8) or ((c ushr 12 and 15) shl 4) or (c ushr 4 and 15)
            border[b]++
            borderCount++
          }
        }
      }
      if (borderCount == 0) return
      val dominant = border.indices.maxBy { border[it] }
      val br = (dominant ushr 8 and 15) * 17
      val bg = (dominant ushr 4 and 15) * 17
      val bb = (dominant and 15) * 17
      val contrasts = IntArray(src.size)
      val histogram = IntArray(256)
      var count = 0
      for (i in src.indices) {
        if (i % size == 0) check()
        val fgAlpha = foreground[i] ushr 24
        if (fgAlpha < 16) continue
        val c = src[i]
        val d = max(abs((c ushr 16 and 255) - br),
          max(abs((c ushr 8 and 255) - bg), abs((c and 255) - bb)))
        contrasts[i] = d
        if (fgAlpha >= 32) {
          histogram[d]++
          count++
        }
      }
      var remaining = count / 50
      var peak = 255
      while (peak > 0 && remaining >= histogram[peak]) remaining -= histogram[peak--]
      bgPeak = peak
      bgContrasts = contrasts
    }

    val n = foreground.size
    val result = IntArray(n) { i ->
      val a = minOf(foreground[i] ushr 24, plate[i] ushr 24)
      if (a < 24) (a shl 24) or 0x00ffffff else 0x00ffffff
    }
    val visited = BooleanArray(n)
    val queue = IntArray(n)
    val buckets = IntArray(32 * 32 * 32)
    val touchedBuckets = IntArray(min(n, 32 * 32 * 32))
    val histogram = IntArray(256)

    for (start in 0 until n) {
      if (start % size == 0) check()
      if (visited[start] || (foreground[start] ushr 24) < 24) continue
      // Connected component BFS (4-neighborhood) over visible foreground pixels.
      var head = 0
      var tail = 0
      visited[start] = true
      queue[tail++] = start
      while (head < tail) {
        val cur = queue[head++]
        if (head and 255 == 0) check()
        val cx = cur % size
        val cy = cur / size
        if (cx > 0) {
          val ni = cur - 1
          if (!visited[ni] && (foreground[ni] ushr 24) >= 24) { visited[ni] = true; queue[tail++] = ni }
        }
        if (cx + 1 < size) {
          val ni = cur + 1
          if (!visited[ni] && (foreground[ni] ushr 24) >= 24) { visited[ni] = true; queue[tail++] = ni }
        }
        if (cy > 0) {
          val ni = cur - size
          if (!visited[ni] && (foreground[ni] ushr 24) >= 24) { visited[ni] = true; queue[tail++] = ni }
        }
        if (cy + 1 < size) {
          val ni = cur + size
          if (!visited[ni] && (foreground[ni] ushr 24) >= 24) { visited[ni] = true; queue[tail++] = ni }
        }
      }

      // Collect opaque core (fallback to >= 48 alpha for translucent glass icons like SMS).
      var coreThreshold = 192
      var coreCount = 0
      for (k in 0 until tail) if ((foreground[queue[k]] ushr 24) >= 192) coreCount++
      if (coreCount < max(4, tail / 4)) {
        coreThreshold = 48
        coreCount = 0
        for (k in 0 until tail) if ((foreground[queue[k]] ushr 24) >= 48) coreCount++
      }
      if (coreCount == 0) {
        for (k in 0 until tail) {
          val idx = queue[k]
          result[idx] = (minOf(foreground[idx] ushr 24, plate[idx] ushr 24) shl 24) or 0x00ffffff
        }
        continue
      }

      // Find dominant 5-bit quantized RGB bucket in this component.
      var touchedCount = 0
      var bestBucket = 0
      var bestBucketCount = 0
      for (k in 0 until tail) {
        val c = foreground[queue[k]]
        if (c ushr 24 < coreThreshold) continue
        val b = ((c ushr 19 and 31) shl 10) or ((c ushr 11 and 31) shl 5) or (c ushr 3 and 31)
        val count = buckets[b] + 1
        if (count == 1) touchedBuckets[touchedCount++] = b
        buckets[b] = count
        if (count > bestBucketCount) {
          bestBucketCount = count
          bestBucket = b
        }
      }
      for (t in 0 until touchedCount) buckets[touchedBuckets[t]] = 0

      val dr0 = (bestBucket ushr 10 and 31) * 8 + 4
      val dg0 = (bestBucket ushr 5 and 31) * 8 + 4
      val db0 = (bestBucket and 31) * 8 + 4
      var sr = 0L; var sg = 0L; var sb = 0L; var sc = 0
      for (k in 0 until tail) {
        val c = foreground[queue[k]]
        if (c ushr 24 < coreThreshold) continue
        val r = c ushr 16 and 255; val g = c ushr 8 and 255; val b = c and 255
        if (max(abs(r - dr0), max(abs(g - dg0), abs(b - db0))) <= 32) {
          sr += r; sg += g; sb += b; sc++
        }
      }
      val dr = if (sc > 0) (sr / sc).toInt() else dr0
      val dg = if (sc > 0) (sg / sc).toInt() else dg0
      val db = if (sc > 0) (sb / sc).toInt() else db0

      histogram.fill(0)
      var detailCount = 0
      for (k in 0 until tail) {
        val c = foreground[queue[k]]
        if (c ushr 24 < coreThreshold) continue
        val dist = max(abs((c ushr 16 and 255) - dr),
          max(abs((c ushr 8 and 255) - dg), abs((c and 255) - db)))
        histogram[dist]++
        if (dist >= 42) detailCount++
      }
      var remaining = coreCount / 50
      var peak = 255
      while (peak > 0 && remaining >= histogram[peak]) remaining -= histogram[peak--]

      // 1. Single-color or softly shaded component: keep authored alpha verbatim.
      if (peak < 48 || detailCount * 100 < coreCount * 3) {
        for (k in 0 until tail) {
          val idx = queue[k]
          result[idx] = (minOf(foreground[idx] ushr 24, plate[idx] ushr 24) shl 24) or 0x00ffffff
        }
        continue
      }

      // 2. Dominant-body component with enclosed interior details (Settings gear, WeType P+, JD face, Calculator =, Cainiao):
      if (sc * 100 >= coreCount * 42) {
        // Detect 3-tone components that have a dominant body, a primary dark/contrasting cutout,
        // AND a distinct third accent color (e.g. Cainiao: white body + black visor + cyan smile/feet).
        var nonBodyCount = 0
        var cutTouched = 0
        var cutBestBucket = 0
        var cutBestCount = 0
        for (k in 0 until tail) {
          val c = foreground[queue[k]]
          if (c ushr 24 < coreThreshold) continue
          val r = c ushr 16 and 255; val g = c ushr 8 and 255; val b = c and 255
          if (max(abs(r - dr), max(abs(g - dg), abs(b - db))) < 48) continue
          nonBodyCount++
          val bk = ((r ushr 3 and 31) shl 10) or ((g ushr 3 and 31) shl 5) or (b ushr 3 and 31)
          val count = buckets[bk] + 1
          if (count == 1) touchedBuckets[cutTouched++] = bk
          buckets[bk] = count
          if (count > cutBestCount) {
            cutBestCount = count
            cutBestBucket = bk
          }
        }
        for (t in 0 until cutTouched) buckets[touchedBuckets[t]] = 0

        var hasThirdTone = false
        var kr = 0; var kg = 0; var kb = 0
        if (cutBestCount > 0 && nonBodyCount * 100 >= coreCount * 12) {
          val kr0 = (cutBestBucket ushr 10 and 31) * 8 + 4
          val kg0 = (cutBestBucket ushr 5 and 31) * 8 + 4
          val kb0 = (cutBestBucket and 31) * 8 + 4
          var ksr = 0L; var ksg = 0L; var ksb = 0L; var ksc = 0
          for (k in 0 until tail) {
            val c = foreground[queue[k]]
            if (c ushr 24 < coreThreshold) continue
            val r = c ushr 16 and 255; val g = c ushr 8 and 255; val b = c and 255
            if (max(abs(r - dr), max(abs(g - dg), abs(b - db))) < 48) continue
            if (max(abs(r - kr0), max(abs(g - kg0), abs(b - kb0))) <= 36) {
              ksr += r; ksg += g; ksb += b; ksc++
            }
          }
          kr = if (ksc > 0) (ksr / ksc).toInt() else kr0
          kg = if (ksc > 0) (ksg / ksc).toInt() else kg0
          kb = if (ksc > 0) (ksb / ksc).toInt() else kb0
          var thirdCount = 0
          for (k in 0 until tail) {
            val c = foreground[queue[k]]
            if (c ushr 24 < coreThreshold) continue
            val r = c ushr 16 and 255; val g = c ushr 8 and 255; val b = c and 255
            val dBody = max(abs(r - dr), max(abs(g - dg), abs(b - db)))
            val dCut = max(abs(r - kr), max(abs(g - kg), abs(b - kb)))
            if (dBody >= 64 && dCut >= 84) thirdCount++
          }
          if (ksc * 100 >= nonBodyCount * 40 && thirdCount * 100 >= coreCount * 3) {
            hasThirdTone = true
          }
        }

        val denom = max(36f, (peak - 20).toFloat())
        for (k in 0 until tail) {
          val idx = queue[k]
          val c = foreground[idx]
          val a = minOf(c ushr 24, plate[idx] ushr 24)
          val r = c ushr 16 and 255; val g = c ushr 8 and 255; val b = c and 255
          val dist = max(abs(r - dr), max(abs(g - dg), abs(b - db)))
          val knockout = ((dist - 20f) / denom).coerceIn(0f, 1f)
          var factor = 1f - knockout
          if (hasThirdTone && knockout > 0.2f) {
            val dCut = max(abs(r - kr), max(abs(g - kg), abs(b - kb)))
            val accent = ((dCut - 36f) / 84f).coerceIn(0f, 1f)
            factor = max(factor, accent * 0.88f)
          }
          val outAlpha = (a * factor).roundToInt()
          result[idx] = (outAlpha shl 24) or 0x00ffffff
        }
        continue
      }

      // 3. Multi-color collage component (Gallery 7 petals, Outlook, Weibo):
      ensureBorderContrast()
      val contrasts = bgContrasts
      var usedBorder = false
      if (contrasts != null && bgPeak >= 36) {
        histogram.fill(0)
        var bgCore = 0
        for (k in 0 until tail) {
          val idx = queue[k]
          if ((foreground[idx] ushr 24) >= coreThreshold) {
            histogram[contrasts[idx]]++
            bgCore++
          }
        }
        if (bgCore >= 8) {
          var remLo = bgCore * 8 / 100
          var p08 = 0
          while (p08 < 255 && remLo >= histogram[p08]) remLo -= histogram[p08++]
          var remHi = bgCore * 8 / 100
          var p92 = 255
          while (p92 > 0 && remHi >= histogram[p92]) remHi -= histogram[p92--]
          if (p92 - p08 >= 48) {
            val denom = max(24f, (bgPeak - 12).toFloat())
            for (k in 0 until tail) {
              val idx = queue[k]
              val a = minOf(foreground[idx] ushr 24, plate[idx] ushr 24)
              val t = ((contrasts[idx] - 12f) / denom).coerceIn(0f, 1f)
              result[idx] = ((a * t).roundToInt() shl 24) or 0x00ffffff
            }
            usedBorder = true
          }
        }
      }
      if (!usedBorder) {
        histogram.fill(0)
        var lumCore = 0
        for (k in 0 until tail) {
          val c = foreground[queue[k]]
          if (c ushr 24 >= coreThreshold) {
            val lum = ((c ushr 16 and 255) * 77 + (c ushr 8 and 255) * 150 + (c and 255) * 29) ushr 8
            histogram[lum]++
            lumCore++
          }
        }
        var remLo = lumCore / 20
        var lo = 0
        while (lo < 255 && remLo >= histogram[lo]) remLo -= histogram[lo++]
        var remHi = lumCore / 20
        var hi = 255
        while (hi > 0 && remHi >= histogram[hi]) remHi -= histogram[hi--]
        val span = max(32f, (hi - lo).toFloat())
        for (k in 0 until tail) {
          val idx = queue[k]
          val c = foreground[idx]
          val a = minOf(c ushr 24, plate[idx] ushr 24)
          val lum = ((c ushr 16 and 255) * 77 + (c ushr 8 and 255) * 150 + (c and 255) * 29) ushr 8
          val t = ((lum - lo) / span).coerceIn(0f, 1f)
          result[idx] = ((a * (0.15f + 0.85f * t)).roundToInt() shl 24) or 0x00ffffff
        }
      }
    }
    return result
  }
}
