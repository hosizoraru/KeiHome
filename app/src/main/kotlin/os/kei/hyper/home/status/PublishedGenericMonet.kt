package os.kei.hyper.home.status

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.*

/** Adds native palette layers only to static artwork; authored programs and custom choices win. */
internal object PublishedGenericMonet {
  private const val MAX_IMAGE = 4 * 1024 * 1024
  private val flat = Regex("res/drawable(?:-[A-Za-z0-9]+)*/([^/]+)\\.png")

  fun write(source: File, output: File, enabled: Boolean,
    excluded: PreservedIconResources, insetFullBleed: Boolean = false,
    generatedPaletteNames: Set<String> = emptySet(), check: () -> Unit = {}): Boolean {
    check()
    if (!enabled && !insetFullBleed) return false
    require(source.canonicalFile != output.canonicalFile && !output.exists())
    val replacements = linkedMapOf<String, ByteArray>()
    var bytes = 0L
    ZipFile(source).use { zip ->
      val paths = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
      val fancyGroups = paths.filter { it.startsWith("fancy_icons/") }
        .groupBy { it.split('/', limit = 3)[1] }
      val nativeGroups = paths.filter { it.startsWith("res/drawable-xxhdpi/") &&
        !it.endsWith('/') && it.count { c -> c == '/' } == 3 }
        .groupBy { it.split('/')[2] }
      val nightGroups = paths.filter { it.startsWith("nightmode/res/drawable-xxhdpi/") &&
        !it.endsWith('/') && it.count { c -> c == '/' } == 4 }
        .groupBy { it.split('/')[3] }
      val protectedNames = paths.mapNotNull {
        when {
          it.startsWith("layer_animating_icons/") || it.startsWith("animating_icons/") ->
            it.split('/', limit = 3).getOrNull(1)
          it.startsWith("nightmode/fancy_icons/") -> it.split('/', limit = 4).getOrNull(2)
          else -> null
        }
      }.toSet()
      fun read(path: String, limit: Int): ByteArray? {
        check()
        val entry = zip.getEntry(path) ?: return null
        if (entry.size !in 1..limit.toLong()) return null
        return zip.getInputStream(entry).use { it.readNBytes(limit + 1) }.takeIf { it.size <= limit }
      }
      val shape by lazy {
        IconMaskPolicy.fromConfig(read(NativeIconShapeConfig.PATH, NativeIconShapeConfig.MAX_BYTES))
      }
      var insetCoverage: Pair<Int, IntArray>? = null
      fun paddingCoverage(size: Int): IntArray? {
        val mask = shape ?: return null
        insetCoverage?.takeIf { it.first == size }?.let { return it.second }
        check()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = try {
          Canvas(bitmap).drawPath(mask.pathForBounds(Rect(0, 0, size, size)),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
          IntArray(size * size).also { bitmap.getPixels(it, 0, size, 0, 0, size, size) }
        } finally { bitmap.recycle() }
        for (index in pixels.indices) pixels[index] = pixels[index] ushr 24
        return pixels.also { insetCoverage = size to it }
      }
      fun nativePair(name: String, prefix: String): ByteArray? {
        val layers = mutableListOf<Bitmap>()
        try {
          for (index in 0..1) {
            val raw = read("${prefix}res/drawable-xxhdpi/$name/$index.png", MAX_IMAGE) ?: return null
            val png = ImportedStaticPng.normalize(raw, check) ?: return null
            layers += BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
          }
          val size = layers.first().width
          val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
          try {
            val bounds = Rect(0, 0, size, size)
            val canvas = Canvas(output)
            shape?.let { canvas.clipPath(it.pathForBounds(bounds)) }
            val layerBounds = NativeLayerViewport.bounds(bounds)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            layers.forEach { check(); canvas.drawBitmap(it, null, layerBounds, paint) }
            return ByteArrayOutputStream().also {
              check()
              check(output.compress(Bitmap.CompressFormat.PNG, 100, it))
            }.toByteArray()
          } finally { output.recycle() }
        } finally { layers.forEach(Bitmap::recycle) }
      }
      val candidates = paths.mapNotNull { path ->
        flat.matchEntire(path)?.groupValues?.get(1)?.takeIf {
          validNativeIconName(it) && !excluded.containsName(it) &&
            !it.startsWith("icon_") && !it.endsWith(".9")
        }?.let { it to path }
      }.groupBy({ it.first }, { it.second }).toMutableMap()
      for (name in fancyGroups.keys) {
        if (validNativeIconName(name) && !excluded.containsName(name)) candidates.putIfAbsent(name, emptyList())
      }
      for ((name, variants) in candidates) {
        check()
        val root = "fancy_icons/$name/"
        if (name in protectedNames) continue
        val manifest = read(root + "manifest.xml", 64 * 1024)
        if (manifest == null && name in fancyGroups) continue
        // Only export-owned, verified palette trees enter this path. A matching
        // template alone must not opt imported authored programs into resizing.
        if (insetFullBleed && name in generatedPaletteNames && manifest != null &&
          fancyGroups[name].orEmpty().all { it in setOf(
            root + "manifest.xml", root + "day.png", root + "night.png", root + "mask.png", root + "bg.png") }) {
          val padded = insetGeneratedPalette(name, manifest, ::read, ::paddingCoverage, check)
          if (padded != null) {
            bytes += padded.size
            if (bytes > 64L * 1024 * 1024)
              throw IconPackException("Generic Monet artwork exceeds the generation memory budget")
            replacements[root + "mask.png"] = padded
            continue
          }
        }
        if (!enabled) continue
        val pair = if (manifest != null) {
          try { NativeMamlPair.parse(manifest, check) } catch (_: IconPackException) { null } ?: continue
        } else null
        // Unknown assets may be referenced by an authored program; never replace that tree.
        if (pair != null && fancyGroups[name].orEmpty().any {
            it !in setOf(root + "manifest.xml", root + pair.light, root + pair.dark) }) continue
        val path = variants.sorted().firstOrNull { "xxhdpi" in it } ?: variants.sorted().firstOrNull()
        // Native pairs outrank their flat aliases. Unknown/incomplete sets stay untouched.
        val useNativePair = pair == null && name in nativeGroups
        if (useNativePair && nativeGroups[name]!!.toSet() !=
          (0..1).map { "res/drawable-xxhdpi/$name/$it.png" }.toSet()) continue
        if (pair == null && nightGroups[name]?.any {
            it !in (0..1).map { index -> "nightmode/res/drawable-xxhdpi/$name/$index.png" }
          } == true) continue
        val sourceDay = if (useNativePair) nativePair(name, "") ?: continue
          else read(pair?.let { root + it.light } ?: path ?: continue, MAX_IMAGE) ?: continue
        val sourceNight = if (pair == null && name in nightGroups)
          nativePair(name, "nightmode/") ?: read("nightmode/$path", MAX_IMAGE) ?: sourceDay
          else read(pair?.let { root + it.dark } ?: "nightmode/$path", MAX_IMAGE) ?: sourceDay
        val day = ImportedStaticPng.normalize(sourceDay, check) ?: continue
        val night = if (sourceNight === sourceDay) day
          else ImportedStaticPng.normalize(sourceNight, check) ?: continue
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(day, 0, day.size, bounds)
        if (bounds.outWidth !in 32..512 || bounds.outHeight != bounds.outWidth ||
          bounds.outMimeType != "image/png") continue
        val nightBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(night, 0, night.size, nightBounds)
        if (nightBounds.outWidth != bounds.outWidth || nightBounds.outHeight != bounds.outHeight ||
          nightBounds.outMimeType != "image/png") continue
        val bitmap = BitmapFactory.decodeByteArray(day, 0, day.size) ?: continue
        try {
          val size = bitmap.width
          val pixels = IntArray(size * size)
          bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
          val extracted = GenericMonetMask.foreground(pixels, size, check) ?: continue
          val foreground = if (insetFullBleed)
            GenericMonetMask.insetFullBleed(extracted, size, paddingCoverage(size), check) else extracted
          fun png(colors: IntArray): ByteArray {
            check()
            val mask = Bitmap.createBitmap(colors, size, size, Bitmap.Config.ARGB_8888)
            return try {
              ByteArrayOutputStream().also {
                check(mask.compress(Bitmap.CompressFormat.PNG, 100, it))
              }.toByteArray()
            } finally { mask.recycle() }
          }
          val layers = NativePaletteIcon.entries(name, day, night, png(foreground),
            png(GenericMonetMask.background(pixels, size, check)))
            .filterKeys { it.startsWith(root) }.toMutableMap()
          // A fancy-only native icon stays fancy-only. Once either canonical flat
          // alias exists, publish both aliases from the exact same day/night pair.
          val dayFallback = "res/drawable-xxhdpi/$name.png"
          val nightFallback = "nightmode/$dayFallback"
          if (dayFallback in paths || nightFallback in paths) {
            layers[dayFallback] = day
            layers[nightFallback] = night
          }
          val added = layers.values.sumOf { it.size.toLong() }
          if (bytes + added > 64L * 1024 * 1024) {
            throw IconPackException("Generic Monet artwork exceeds the generation memory budget")
          }
          bytes += added
          replacements.putAll(layers)
        } finally { bitmap.recycle() }
      }
    }
    if (replacements.isEmpty()) return false
    RawIconArchive.write(source, output, replacements = replacements, check = check,
      maxOutputBytes = IconPackLimits().maxInputBytes)
    return true
  }

  private fun insetGeneratedPalette(name: String, manifest: ByteArray,
    read: (String, Int) -> ByteArray?, coverage: (Int) -> IntArray?, check: () -> Unit): ByteArray? {
    val root = "fancy_icons/$name/"
    val day = read(root + "day.png", MAX_IMAGE) ?: return null
    val night = read(root + "night.png", MAX_IMAGE) ?: day
    val mask = read(root + "mask.png", MAX_IMAGE) ?: return null
    val background = read(root + "bg.png", MAX_IMAGE) ?: return null
    if (!NativePaletteIcon.recognizes(name, manifest, day, night, mask, background)) return null
    val size = GeneratedIconPng.squareSize(mask)
    check()
    val bitmap = BitmapFactory.decodeByteArray(mask, 0, mask.size) ?: return null
    try {
      val pixels = IntArray(size * size)
      bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
      val padded = GenericMonetMask.insetFullBleed(pixels, size, coverage(size), check)
      if (padded === pixels) return null
      val output = Bitmap.createBitmap(padded, size, size, Bitmap.Config.ARGB_8888)
      return try {
        check()
        ByteArrayOutputStream().also {
          require(output.compress(Bitmap.CompressFormat.PNG, 100, it))
        }.toByteArray().also { check() }
      } finally { output.recycle() }
    } finally { bitmap.recycle() }
  }
}
