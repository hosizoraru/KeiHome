package os.kei.hyper.home.iconpack.perfect

/** Pixel-only operations shared by static icons and native animation textures. */
internal object PerfectNightPixels {
  fun dim(pixel: Int): Int {
    fun channel(shift: Int) = (((pixel ushr shift) and 255) * 214 + 127) / 255
    return (pixel and -0x1000000) or
      (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }

  /** A smooth, low-range native background is one plate, including radial gradients. */
  fun backgroundPlate(
    pixels: IntArray, width: Int, height: Int, check: () -> Unit,
  ): BooleanArray? {
    val mins = intArrayOf(255, 255, 255)
    val maxs = IntArray(3)
    var visible = 0
    var roughPairs = 0
    var pairs = 0
    val inset = (minOf(width, height) / 64).coerceIn(1, 6)
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        val p = pixels[i]
        if (p ushr 24 < 128) continue
        visible++
        val interior = x in inset until width - inset && y in inset until height - inset
        for (c in 0..2) {
          val value = (p ushr (c * 8)) and 255
          if (interior) {
            mins[c] = minOf(mins[c], value)
            maxs[c] = maxOf(maxs[c], value)
          }
        }
        fun pair(j: Int) {
          if (pixels[j] ushr 24 < 128) return
          pairs++
          if ((0..2).any { kotlin.math.abs((p ushr (it * 8) and 255) -
              (pixels[j] ushr (it * 8) and 255)) > 8 }) roughPairs++
        }
        if (interior && x > inset) pair(i - 1)
        if (interior && y > inset) pair(i - width)
      }
    }
    if (pairs > 0 && roughPairs.toLong() * 200 <= pairs &&
      visible.toLong() * 5 >= pixels.size.toLong() * 3 &&
      (0..2).all { maxs[it] - mins[it] <= 96 }) {
      val background = BooleanArray(pixels.size)
      for (y in 0 until height) {
        check()
        for (x in 0 until width) {
          val i = y * width + x
          background[i] = pixels[i] ushr 24 >= 32
        }
      }
      return background
    }
    return plate(pixels, width, height, check)
  }

  /**
   * Require a large edge-connected plate before separating baked artwork from its background.
   * Edge agreement and a bounded foreground palette exclude full-bleed and photographic art.
   * All decisions use unpremultiplied RGB; alpha is never changed.
   */
  fun plate(pixels: IntArray, width: Int, height: Int, check: () -> Unit): BooleanArray? {
    require(width > 0 && height > 0 && pixels.size == width * height)
    val uniform = uniformPlate(pixels, width, height, check)
    val gradient = gradientPlate(pixels, width, height, check)
    val plate = when {
      uniform == null -> gradient
      gradient != null && gradient.count { it } > uniform.count { it } -> gradient
      else -> uniform
    } ?: return null
    extendNeutralShadow(pixels, width, height, plate, check)
    return plate
  }

  private fun extendNeutralShadow(
    pixels: IntArray, width: Int, height: Int, plate: BooleanArray, check: () -> Unit,
  ) {
    fun paleNeutral(p: Int): Boolean {
      val r = p ushr 16 and 255
      val g = p ushr 8 and 255
      val b = p and 255
      return p ushr 24 >= 32 && minOf(r, g, b) >= 160 && maxOf(r, g, b) - minOf(r, g, b) <= 32
    }
    var selected = 0
    var pale = 0
    for (i in pixels.indices) if (plate[i]) {
      selected++
      if (paleNeutral(pixels[i])) pale++
    }
    if (pale.toLong() * 10 < selected.toLong() * 9) return
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 0
    for (i in pixels.indices) if (plate[i]) queue[tail++] = i
    fun add(i: Int, from: Int) {
      if (plate[i] || !paleNeutral(pixels[i])) return
      val x = i % width
      // A soft shadow is smooth on every side; hard glyph edges and colored art stop it.
      fun similar(j: Int): Boolean =
        (0..2).all { kotlin.math.abs((pixels[i] ushr (it * 8) and 255) -
          (pixels[j] ushr (it * 8) and 255)) <= 6 }
      if (!similar(from)) return
      fun smooth(j: Int): Boolean = !paleNeutral(pixels[j]) || similar(j)
      if (x > 0 && !smooth(i - 1) || x + 1 < width && !smooth(i + 1) ||
        i >= width && !smooth(i - width) || i + width < pixels.size && !smooth(i + width)) return
      plate[i] = true
      queue[tail++] = i
    }
    while (head < tail) {
      if (head % 1024 == 0) check()
      val i = queue[head++]
      val x = i % width
      if (x > 0) add(i - 1, i)
      if (x + 1 < width) add(i + 1, i)
      if (i >= width) add(i - width, i)
      if (i + width < pixels.size) add(i + width, i)
    }
  }

  private fun uniformPlate(
    pixels: IntArray, width: Int, height: Int, check: () -> Unit,
  ): BooleanArray? {
    val edges = IntArray(2 * (width + height))
    var edgeCount = 0
    var visible = 0
    for (y in 0 until height) {
      check()
      var first = -1
      var last = -1
      var firstSolid = -1
      var lastSolid = -1
      for (x in 0 until width) {
        val i = y * width + x
        if (pixels[i] ushr 24 < 128) continue
        visible++
        if (first < 0) first = i
        last = i
        if (pixels[i] ushr 24 >= 250) {
          if (firstSolid < 0) firstSolid = i
          lastSolid = i
        }
      }
      if (first >= 0) {
        edges[edgeCount++] = if (firstSolid >= 0) firstSolid else first
        edges[edgeCount++] = if (lastSolid >= 0) lastSolid else last
      }
    }
    if (visible.toLong() * 5 < pixels.size.toLong() * 3) return null
    for (x in 0 until width) {
      check()
      var first = -1
      var last = -1
      var firstSolid = -1
      var lastSolid = -1
      for (y in 0 until height) {
        val i = y * width + x
        if (pixels[i] ushr 24 < 128) continue
        if (first < 0) first = i
        last = i
        if (pixels[i] ushr 24 >= 250) {
          if (firstSolid < 0) firstSolid = i
          lastSolid = i
        }
      }
      if (first >= 0) {
        edges[edgeCount++] = if (firstSolid >= 0) firstSolid else first
        edges[edgeCount++] = if (lastSolid >= 0) lastSolid else last
      }
    }
    if (edgeCount == 0) return null
    fun median(shift: Int): Int {
      val counts = IntArray(256)
      for (n in 0 until edgeCount) counts[(pixels[edges[n]] ushr shift) and 255]++
      var count = 0
      for (v in counts.indices) {
        count += counts[v]
        if (count * 2 >= edgeCount) return v
      }
      return 0
    }
    val red = median(16)
    val green = median(8)
    val blue = median(0)
    fun tolerance(shift: Int, center: Int): Int {
      val differences = IntArray(256)
      for (n in 0 until edgeCount)
        differences[kotlin.math.abs(((pixels[edges[n]] ushr shift) and 255) - center)]++
      var count = 0
      for (v in differences.indices) {
        count += differences[v]
        if (count.toLong() * 20 >= edgeCount.toLong() * 19) return (v + 6).coerceIn(6, 48)
      }
      return 6
    }
    val redTolerance = tolerance(16, red)
    val greenTolerance = tolerance(8, green)
    val blueTolerance = tolerance(0, blue)
    val neutralBorder = maxOf(red, green, blue) - minOf(red, green, blue) <= 24 &&
      minOf(red, green, blue) >= 192 && maxOf(redTolerance, greenTolerance, blueTolerance) <= 24
    fun matches(pixel: Int): Boolean = pixel ushr 24 >= 32 &&
      kotlin.math.abs(((pixel ushr 16) and 255) - red) <= redTolerance &&
      kotlin.math.abs(((pixel ushr 8) and 255) - green) <= greenTolerance &&
      kotlin.math.abs((pixel and 255) - blue) <= blueTolerance
    if ((0 until edgeCount).count { matches(pixels[edges[it]]) }.toLong() * 10 < edgeCount.toLong() * 9)
      return null

    val background = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var head = 0
    var tail = 0
    fun add(i: Int) {
      if (!background[i] && matches(pixels[i])) {
        background[i] = true
        queue[tail++] = i
      }
    }
    for (n in 0 until edgeCount) add(edges[n])
    var connected = 0
    while (head < tail) {
      if (head % 1024 == 0) check()
      val i = queue[head++]
      if (pixels[i] ushr 24 >= 128) connected++
      val x = i % width
      if (x > 0) add(i - 1)
      if (x + 1 < width) add(i + 1)
      if (i >= width) add(i - width)
      if (i + width < pixels.size) add(i + width)
    }
    if (connected.toLong() * 5 < visible.toLong() * 2) return null
    var neighbors = 0L
    var rough = 0L
    fun compare(a: Int, b: Int) {
      if (!background[b] || pixels[b] ushr 24 < 128) return
      neighbors++
      if ((0..2).any {
        kotlin.math.abs(((pixels[a] ushr (it * 8)) and 255) -
          ((pixels[b] ushr (it * 8)) and 255)) > 12
      }) rough++
    }
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        if (!background[i] || pixels[i] ushr 24 < 128) continue
        if (x > 0) compare(i, i - 1)
        if (y > 0) compare(i, i - width)
      }
    }
    // A low-range photograph is not a plate merely because all its colors are similar.
    if (rough * 50 > neighbors) return null
    val palette = BooleanArray(4096)
    var colors = 0
    var foreground = 0
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        val pixel = pixels[i]
        if (pixel ushr 24 < 128 || matches(pixel)) continue
        foreground++
        val color = ((pixel ushr 12) and 0xf00) or
          ((pixel ushr 8) and 0xf0) or ((pixel ushr 4) and 0xf)
        if (!palette[color]) {
          palette[color] = true
          colors++
        }
      }
    }
    if (foreground.toLong() * (if (neutralBorder) 8 else 2) >
      visible.toLong() * (if (neutralBorder) 5 else 1) ||
      (!neutralBorder && foreground.toLong() * 8 > visible && colors > 96)) return null
    // Include enclosed counters in a stencil (for example the hole in a letter O).
    // Colored/photo highlights remain isolated from the edge-connected background.
    if (neutralGlyph(pixels, width, height, check) {
      pixels[it] ushr 24 >= 250 && !matches(pixels[it])
    }) {
      for (y in 0 until height) {
        check()
        for (x in 0 until width) {
          val i = y * width + x
          if (matches(pixels[i])) background[i] = true
        }
      }
    }
    return background
  }

  /**
   * Coons interpolation extends the four authored edges into a two-dimensional surface.
   * Only a dominant, edge-connected, smooth region that agrees inside the image is a plate.
   * A smaller surface (such as a calendar's colored header) and unmatched artwork stay intact.
   */
  private fun gradientPlate(
    pixels: IntArray, width: Int, height: Int, check: () -> Unit,
  ): BooleanArray? {
    if (width < 16 || height < 16) return null
    var opaque = 0
    for (y in 0 until height) {
      check()
      for (x in 0 until width) if (pixels[y * width + x] ushr 24 >= 128) opaque++
    }
    if (opaque.toLong() * 5 < pixels.size.toLong() * 3) return null
    fun difference(a: Int, b: Int): Int = maxOf(
      kotlin.math.abs(((a ushr 16) and 255) - ((b ushr 16) and 255)),
      kotlin.math.abs(((a ushr 8) and 255) - ((b ushr 8) and 255)),
      kotlin.math.abs((a and 255) - (b and 255)),
    )
    val inset = (minOf(width, height) / 64).coerceIn(2, 6)
    fun curve(length: Int, cross: Int, index: (Int, Int) -> Int): IntArray? {
      val samples = IntArray(length)
      val valid = BooleanArray(length)
      for (t in 0 until length) {
        if (t % 64 == 0) check()
        var first = -1
        var last = -1
        for (v in 0 until cross) if (pixels[index(t, v)] ushr 24 >= 250) {
          if (first < 0) first = v
          last = v
        }
        if (first >= 0) {
          samples[t] = pixels[index(t, minOf(first + inset, (first + last) / 2))]
          valid[t] = true
        }
      }
      val first = valid.indexOfFirst { it }
      if (first < 0 || valid.count { it } * 5 < length * 3) return null
      var previous = first
      for (t in samples.indices) {
        if (valid[t]) previous = t else samples[t] = samples[previous]
      }
      // Sample inside rounded alpha edges and suppress narrow compression ringing.
      return IntArray(length) { t ->
        var color = -0x1000000
        for (c in 0..2) {
          val values = (maxOf(0, t - 3)..minOf(length - 1, t + 3))
            .map { samples[it] ushr (c * 8) and 255 }.sorted()
          color = color or (values[values.size / 2] shl (c * 8))
        }
        color
      }
    }
    val left = curve(height, width) { y, x -> y * width + x } ?: return null
    val right = curve(height, width) { y, x -> y * width + width - 1 - x } ?: return null
    val top = curve(width, height) { x, y -> y * width + x } ?: return null
    val bottom = curve(width, height) { x, y -> (height - 1 - y) * width + x } ?: return null
    for (edge in listOf(left, right, top, bottom))
      if ((inset * 2 until edge.size - inset * 2)
          .count { difference(edge[it - 1], edge[it]) > 12 } * 100 > edge.size)
        return null

    val matching = BooleanArray(pixels.size)
    for (y in 0 until height) {
      check()
      val v = (y - inset).toDouble() / (height - 1 - inset * 2)
      for (x in 0 until width) {
        val i = y * width + x
        if (pixels[i] ushr 24 < 32) continue
        val u = (x - inset).toDouble() / (width - 1 - inset * 2)
        var agrees = true
        for (c in 0..2) {
          val shift = c * 8
          fun value(pixel: Int) = (pixel ushr shift) and 255
          val corners = (1 - u) * (1 - v) * value(top[inset]) +
            u * (1 - v) * value(top[width - 1 - inset]) +
            (1 - u) * v * value(bottom[inset]) +
            u * v * value(bottom[width - 1 - inset])
          val expected = ((1 - u) * value(left[y]) + u * value(right[y]) +
            (1 - v) * value(top[x]) + v * value(bottom[x]) - corners).coerceIn(0.0, 255.0)
          if (kotlin.math.abs(value(pixels[i]) - expected) > 16) {
            agrees = false
            break
          }
        }
        matching[i] = agrees
      }
    }
    val labels = IntArray(pixels.size)
    val queue = IntArray(pixels.size)
    var label = 0
    var largest = 0
    var runnerUp = 0
    var selected = 0
    var selectedCore = 0
    var selectedEdges = 0
    val insetX = width / 10
    val insetY = height / 10
    for (start in pixels.indices) {
      if (start % 1024 == 0) check()
      if (!matching[start] || labels[start] != 0) continue
      label++
      var head = 0
      var tail = 1
      var core = 0
      var edges = 0
      queue[0] = start
      labels[start] = label
      fun add(i: Int, from: Int) {
        if (matching[i] && labels[i] == 0 && difference(pixels[i], pixels[from]) <= 6) {
          labels[i] = label
          queue[tail++] = i
        }
      }
      while (head < tail) {
        if (head % 1024 == 0) check()
        val i = queue[head++]
        val x = i % width
        val y = i / width
        if (x in insetX until width - insetX && y in insetY until height - insetY) core++
        if (x <= inset * 2) edges = edges or 1
        if (x >= width - 1 - inset * 2) edges = edges or 2
        if (y <= inset * 2) edges = edges or 4
        if (y >= height - 1 - inset * 2) edges = edges or 8
        if (x > 0) add(i - 1, i)
        if (x + 1 < width) add(i + 1, i)
        if (y > 0) add(i - width, i)
        if (y + 1 < height) add(i + width, i)
      }
      if (tail > largest) {
        runnerUp = largest
        largest = tail
        selected = label
        selectedCore = core
        selectedEdges = edges
      } else runnerUp = maxOf(runnerUp, tail)
    }
    val coreArea = (width - 2 * insetX).toLong() * (height - 2 * insetY)
    if (largest.toLong() * 2 < opaque || largest.toLong() * 10 < runnerUp.toLong() * 11 ||
      selectedCore.toLong() * 5 < coreArea || Integer.bitCount(selectedEdges) < 3) return null

    var neighbors = 0L
    var rough = 0L
    fun foregroundPair(a: Int, b: Int) {
      if (labels[b] == selected || pixels[b] ushr 24 < 250) return
      neighbors++
      if (difference(pixels[a], pixels[b]) > 16) rough++
    }
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        if (labels[i] == selected || pixels[i] ushr 24 < 250) continue
        if (x > 0) foregroundPair(i, i - 1)
        if (y > 0) foregroundPair(i, i - width)
      }
    }
    if (rough * 12 > neighbors) return null
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        matching[i] = labels[i] == selected
      }
    }
    return matching
  }

  fun recolorPlate(
    pixels: IntArray, width: Int, height: Int, background: BooleanArray, check: () -> Unit,
  ) {
    val liftGlyph = neutralGlyph(pixels, width, height, check) {
      pixels[it] ushr 24 >= 250 && !background[it]
    }
    for (y in 0 until height) {
      check()
      val gray = 56 - (35 * y + maxOf(1, height - 1) / 2) / maxOf(1, height - 1)
      for (x in 0 until width) {
        val i = y * width + x
        val pixel = pixels[i]
        pixels[i] = if (background[i]) (pixel and -0x1000000) or
          (gray shl 16) or (gray shl 8) or gray
        else if (liftGlyph && pixel ushr 24 >= 250) lift(pixel) else pixel
      }
    }
  }

  fun bakedForegroundPlate(
    pixels: IntArray, width: Int, height: Int, check: () -> Unit,
  ): BooleanArray? {
    var opaque = 0
    for (y in 0 until height) {
      check()
      for (x in 0 until width) if (pixels[y * width + x] ushr 24 >= 250) opaque++
    }
    if (opaque.toLong() * 20 < pixels.size.toLong() * 19) return null
    val background = plate(pixels, width, height, check) ?: return null
    var artwork = 0
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        if (!background[i] && pixels[i] ushr 24 >= 128) artwork++
      }
    }
    // A solid foreground sprite is artwork, rather than an empty icon background.
    return background.takeIf { artwork.toLong() * 50 >= opaque }
  }

  fun liftForeground(pixels: IntArray, width: Int, height: Int, check: () -> Unit): Boolean {
    if (!isolatedForeground(pixels, width, height, check)) return false
    if (!neutralGlyph(pixels, width, height, check, opaqueCore = true) { true }) return false
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        pixels[i] = lift(pixels[i])
      }
    }
    return true
  }

  fun liftForeground(
    pixels: IntArray, underlay: IntArray, width: Int, height: Int, check: () -> Unit,
  ): Boolean {
    if (!isolatedForeground(pixels, width, height, check)) return false
    if (!neutralGlyph(pixels, width, height, check, opaqueCore = true) { true }) return false
    var visible = 0L
    var dark = 0L
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        if (pixels[i] ushr 24 < 32) continue
        visible++
        val p = underlay[i]
        val luma = (((p ushr 16) and 255) * 2126 +
          ((p ushr 8) and 255) * 7152 + (p and 255) * 722) / 10000
        if (p ushr 24 >= 128 && luma <= 96) dark++
      }
    }
    if (dark * 10 < visible * 9) return false
    return liftForeground(pixels, width, height, check)
  }

  /** Lift thin, uniform glyph silhouettes independently of neighboring colored artwork. */
  fun liftForegroundComponents(
    pixels: IntArray, underlay: IntArray, width: Int, height: Int, check: () -> Unit,
  ): Boolean {
    if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong() ||
      underlay.size != pixels.size || pixels.size > 4 * 1024 * 1024) return false
    var transparent = 0
    for (y in 0 until height) {
      check()
      for (x in 0 until width) if (pixels[y * width + x] ushr 24 < 32) transparent++
    }
    if (transparent.toLong() * 4 < pixels.size) return false
    val labels = IntArray(pixels.size)
    val queue = IntArray(pixels.size)
    val eligible = BooleanArray(4097)
    val coreSizes = IntArray(eligible.size)
    val fringeSizes = IntArray(eligible.size)
    var components = 0
    fun neutral(p: Int, fringe: Boolean = false): Boolean {
      val r = p ushr 16 and 255
      val g = p ushr 8 and 255
      val b = p and 255
      // A premultiplied byte rounding step grows when unpremultiplying faint AA edges.
      val rounding = if (fringe) (254 + (p ushr 24)) / (p ushr 24) else 0
      return maxOf(r, g, b) <= 96 && maxOf(r, g, b) - minOf(r, g, b) <= 12 + rounding
    }
    for (start in pixels.indices) {
      if (start % 1024 == 0) check()
      if (labels[start] != 0 || pixels[start] ushr 24 < 160) continue
      if (++components >= eligible.size) return false
      val label = components
      var head = 0
      var tail = 1
      var minX = width
      var minY = height
      var maxX = 0
      var maxY = 0
      var opaque = 0
      var darkUnderlay = 0
      var allNeutral = true
      var totalShade = 0L
      val shades = IntArray(97)
      queue[0] = start
      labels[start] = label
      fun add(i: Int) {
        if (labels[i] == 0 && pixels[i] ushr 24 >= 160) {
          labels[i] = label
          queue[tail++] = i
        }
      }
      while (head < tail) {
        if (head % 1024 == 0) check()
        val i = queue[head++]
        val x = i % width
        val y = i / width
        minX = minOf(minX, x)
        maxX = maxOf(maxX, x)
        minY = minOf(minY, y)
        maxY = maxOf(maxY, y)
        val p = pixels[i]
        if (p ushr 24 >= 240) opaque++
        if (!neutral(p)) allNeutral = false
        else {
          val shade = maxOf(p ushr 16 and 255, p ushr 8 and 255, p and 255)
          shades[shade]++
          totalShade += shade
        }
        val below = underlay[i]
        val luma = ((below ushr 16 and 255) * 2126 +
          (below ushr 8 and 255) * 7152 + (below and 255) * 722) / 10000
        if (below ushr 24 >= 128 && luma <= 96) darkUnderlay++
        if (x > 0) add(i - 1)
        if (x + 1 < width) add(i + 1)
        if (y > 0) add(i - width)
        if (y + 1 < height) add(i + width)
      }
      coreSizes[label] = tail
      val boxWidth = maxX - minX + 1
      val boxHeight = maxY - minY + 1
      val boxArea = boxWidth.toLong() * boxHeight
      val shortSide = minOf(boxWidth, boxHeight)
      val longSide = maxOf(boxWidth, boxHeight)
      val thinShape = longSide * 2 <= shortSide * 5 && tail * 5L <= boxArea * 3
      val bar = longSide >= shortSide * 3 && longSide <= shortSide * 12 &&
        tail * 10L >= boxArea * 9
      val mode = shades.indices.maxBy { shades[it] }
      val concentrated = (maxOf(0, mode - 8)..minOf(96, mode + 8)).sumOf { shades[it] }
      var symmetric = 0
      for (n in 0 until tail) {
        if (n % 1024 == 0) check()
        val i = queue[n]
        val opposite = (minY + maxY - i / width) * width + minX + maxX - i % width
        if (labels[opposite] == label) symmetric++
      }
      eligible[label] = allNeutral && tail >= 8 && shortSide >= 2 &&
        minX > 0 && minY > 0 && maxX < width - 1 && maxY < height - 1 &&
        tail * 4L <= pixels.size && tail * 10L >= boxArea && (thinShape || bar) &&
        opaque * 4L >= tail * 3L && darkUnderlay * 20L >= tail * 19L &&
        totalShade <= tail * 64L && concentrated * 20L >= tail * 19L &&
        symmetric * 10L >= tail * 9L
    }
    if (eligible.none { it }) return false

    // All colors participate in fringe ownership. A faint bridge to another silhouette
    // cannot turn the neighboring colored art or its shadow into part of a white glyph.
    val depth = ByteArray(pixels.size)
    var head = 0
    var tail = 0
    for (i in pixels.indices) if (labels[i] != 0) queue[tail++] = i
    val fringeLimit = (minOf(width, height) / 128).coerceIn(1, 4)
    while (head < tail) {
      if (head % 1024 == 0) check()
      val i = queue[head++]
      val label = labels[i]
      val distance = depth[i].toInt()
      val x = i % width
      fun add(j: Int) {
        val alpha = pixels[j] ushr 24
        if (alpha == 0) return
        if (labels[j] != 0) {
          if (labels[j] != label) {
            eligible[label] = false
            eligible[labels[j]] = false
          }
          return
        }
        if (distance >= fringeLimit || alpha > pixels[i] ushr 24) return
        labels[j] = label
        depth[j] = (distance + 1).toByte()
        queue[tail++] = j
        if (alpha >= 32) {
          fringeSizes[label]++
          if (!neutral(pixels[j], fringe = true)) eligible[label] = false
        }
      }
      if (x > 0) add(i - 1)
      if (x + 1 < width) add(i + 1)
      if (i >= width) add(i - width)
      if (i + width < pixels.size) add(i + width)
    }
    for (label in 1..components)
      if (fringeSizes[label] * 2L > coreSizes[label]) eligible[label] = false
    var changed = false
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        if (eligible[labels[i]]) {
          pixels[i] = (pixels[i] and -0x1000000) or 0xffffff
          changed = true
        }
      }
    }
    return changed
  }

  private fun isolatedForeground(
    pixels: IntArray, width: Int, height: Int, check: () -> Unit,
  ): Boolean {
    var transparent = 0
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val p = pixels[y * width + x]
        if (p ushr 24 < 32) transparent++
        else {
          val r = (p ushr 16) and 255
          val g = (p ushr 8) and 255
          val b = p and 255
          if (maxOf(r, g, b) > 96 || maxOf(r, g, b) - minOf(r, g, b) > 12) return false
        }
      }
    }
    return transparent.toLong() * 4 >= pixels.size
  }

  private fun neutralGlyph(
    pixels: IntArray, width: Int, height: Int, check: () -> Unit,
    opaqueCore: Boolean = false, selected: (Int) -> Boolean,
  ): Boolean {
    var visible = 0
    var neutral = 0
    val shades = IntArray(97)
    val coreShades = if (opaqueCore) IntArray(97) else null
    for (y in 0 until height) {
      check()
      for (x in 0 until width) {
        val i = y * width + x
        val p = pixels[i]
        if (!selected(i) || p ushr 24 < 32) continue
        visible++
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        if (hi <= 96 && hi - lo <= 12) {
          neutral++
          shades[hi]++
          if (p ushr 24 >= 240 && coreShades != null) coreShades[hi]++
        }
      }
    }
    if (visible == 0 || neutral.toLong() * 10 < visible.toLong() * 9) return false
    // Antialiased stencils can have many gray fringe/outline shades around a near-black core.
    // Require that core to cover at least half the visible glyph; keep the full-image
    // neutral/color and transparency gates, and retain the old test for faint textures.
    val coreCount = coreShades?.sum() ?: 0
    val useCore = coreShades != null && coreCount.toLong() * 2 >= visible
    val samples = if (useCore) requireNotNull(coreShades) else shades
    val sampleCount = if (useCore) coreCount else visible
    val mode = samples.indices.maxBy { samples[it] }
    val concentrated = (maxOf(0, mode - 12)..minOf(96, mode + 12)).sumOf { samples[it] }
    if (useCore && mode <= 24)
      return concentrated.toLong() * 4 >= sampleCount.toLong() * 3
    return concentrated.toLong() * 10 >= sampleCount.toLong() * 9
  }

  private fun lift(pixel: Int): Int {
    val r = (pixel ushr 16) and 255
    val g = (pixel ushr 8) and 255
    val b = pixel and 255
    return if (maxOf(r, g, b) <= 128 && maxOf(r, g, b) - minOf(r, g, b) <= 12)
      (pixel and -0x1000000) or ((255 - r) shl 16) or ((255 - g) shl 8) or (255 - b)
    else pixel
  }
}
