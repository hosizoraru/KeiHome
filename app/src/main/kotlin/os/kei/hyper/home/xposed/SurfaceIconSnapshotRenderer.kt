package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.BoundedPackXml
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeAppleDynamicIcon
import os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeIconShapeConfig
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.NativeLayerViewport
import os.kei.hyper.home.iconpack.NativeWeatherLayerProgram
import os.kei.hyper.home.iconpack.IconPackException
import os.kei.hyper.home.iconpack.validNativeIconName
import os.kei.hyper.home.iconpack.validDottedName
import os.kei.hyper.home.iconpack.IconGenerationManifest

/** Worker-only static snapshots. Foreign MAML is never interpreted or started. */
internal object SurfaceIconSnapshotRenderer {
  const val SIZE = 192
  private const val IMAGE_LIMIT = 4 * 1024 * 1024
  private val DRAWABLE_BUCKETS = listOf("drawable-xxhdpi", "drawable-xxxhdpi", "drawable-xhdpi", "drawable")
  private val masks = WeakHashMap<ZipFile, NativeAdaptiveIconMask?>()
  private val preserved = WeakHashMap<ZipFile, Set<String>>()

  fun render(
    zip: ZipFile, name: String, dark: Boolean, locale: Locale, now: Long,
    palette: Long = 0L, check: () -> Unit = {}, packageName: String? = null,
    resolvedMask: NativeAdaptiveIconMask? = resolveMask(zip, check),
    originalIcon: (() -> Bitmap?)? = null,
    weatherTemperature: (global: Boolean) -> Int? = { null },
    onDynamic: () -> Unit = {},
  ): Bitmap? {
    if (!validNativeIconName(name) || name.length > 240) return null
    val skipped = synchronized(preserved) {
      preserved[zip] ?: run {
        check()
        val values = zip.getEntry(IconGenerationManifest.PATH)?.let { entry ->
          try { IconGenerationManifest.decode(zip.getInputStream(entry).use {
            it.readNBytes(IconGenerationManifest.MAX_BYTES + 1)
          }).preservedIconPackages } catch (_: IllegalArgumentException) { emptySet() }
        }.orEmpty()
        if (preserved.size >= 8) preserved.clear()
        preserved[zip] = values
        values
      }
    }
    if ((packageName ?: name.substringBefore('#')) in skipped) return originalIcon?.invoke()
    val fallback = packageName?.takeIf { it != name && it.length <= 240 && validDottedName(it) }
    val weather = (packageName ?: name) == WeatherIconTemperature.PACKAGE
    val bitmap = renderArtwork(zip, name, dark, locale, now, palette, check, weather,
      weatherTemperature, onDynamic)
      ?: fallback?.let { renderArtwork(zip, it, dark, locale, now, palette, check, weather,
        weatherTemperature, onDynamic) }
      ?: return null
    return try {
      IconMaskPolicy.clipIfExceeds(bitmap, resolvedMask, check)
    } catch (failure: Throwable) { bitmap.recycle(); throw failure }
  }

  /** Resolve the published contract once per worker archive/generation, including no mask. */
  fun resolveMask(zip: ZipFile, check: () -> Unit = {}): NativeAdaptiveIconMask? {
    check()
    return synchronized(masks) {
      if (masks.containsKey(zip)) return@synchronized masks[zip]
      val entry = zip.getEntry(NativeIconShapeConfig.PATH)
      val bytes = if (entry != null && !entry.isDirectory &&
          entry.size in 1..NativeIconShapeConfig.MAX_BYTES.toLong()) {
        zip.getInputStream(entry).use { it.readNBytes(NativeIconShapeConfig.MAX_BYTES + 1) }
          .takeIf { it.size <= NativeIconShapeConfig.MAX_BYTES }
      } else null
      val mask = IconMaskPolicy.fromConfig(bytes)
      check()
      // Missing masks are cached too. ZIP lifetime/revision stays owned by the
      // publisher; weak keys never retain its archives or require main-thread IO.
      // GC may empty weak keys after size() even while this monitor is held.
      if (masks.size >= 4) masks.keys.firstOrNull()?.let(masks::remove)
      mask.also { masks[zip] = it }
    }
  }

  private fun renderArtwork(
    zip: ZipFile, name: String, dark: Boolean, locale: Locale, now: Long,
    palette: Long, check: () -> Unit, weather: Boolean,
    weatherTemperature: (Boolean) -> Int?, onDynamic: () -> Unit,
  ): Bitmap? {
    fun bytes(path: String, limit: Int = IMAGE_LIMIT): ByteArray? {
      check()
      val entry = zip.getEntry(path) ?: return null
      require(!entry.isDirectory && entry.size in 0..limit.toLong())
      return zip.getInputStream(entry).use { input ->
        input.readNBytes(limit + 1).also { require(it.size <= limit) }
      }
    }
    fun image(path: String): Bitmap? = bytes(path)?.let { decode(it) }
    val root = "fancy_icons/$name/"
    val manifest = bytes(root + "manifest.xml", 64 * 1024)
    if (manifest != null) {
      // Older KeiHome calendars used this fixed accent/text pair (and its night-dimmed variant).
      // Authenticate the entire template after that finite substitution, without interpreting foreign MAML.
      val legacyCalendar = NativeColorOsDynamicIcon.kindOfManifest(
        manifest.toString(Charsets.UTF_8)
          .replace("#FF1969FF", "#FF0A7FFB")
          .replace("#FF101010", "#FF000000")
          .replace("#FF1558D6", "#FF086BD3")
          .replace("#FF0D0D0D", "#FF000000")
          .toByteArray(Charsets.UTF_8),
      ) == NativeColorOsDynamicIcon.Kind.CALENDAR
      val dynamic = legacyCalendar || NativeColorOsDynamicIcon.kindOfManifest(manifest) != null ||
        NativeAppleDynamicIcon.kindOfManifest(manifest) != null
      if (dynamic) {
        onDynamic()
        return dynamic(manifest, dark, locale, now, check) { image(root + it) }
      }
      val pair = try { NativeMamlPair.parse(manifest, check) } catch (_: Exception) { null }
      if (pair != null) {
        // Crossfade packs select a resource folder as well as the declared image.
        val path = if (dark) pair.dark else pair.light
        return (if (dark) image(root + "nightmode/" + path) else null)
          ?: image(root + path)
      }
      val day = bytes(root + "day.png")
      val night = bytes(root + "night.png") ?: day
      val mask = bytes(root + "mask.png")
      val background = bytes(root + "bg.png")
      if (day != null && night != null && mask != null && background != null) {
        val verified = NativePaletteIcon.recognizes(
          root.removePrefix("fancy_icons/").removeSuffix("/"), manifest, day, night, mask, background)
        if (verified) {
          if (palette ushr 48 == 1L) {
            val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            for ((data, color) in listOf(background to palette.toInt(),
                mask to (palette ushr 24).toInt())) {
              val bitmap = decode(data) ?: run { result.recycle(); return null }
              try {
                // A color filter tints the source's alpha; an opaque temporary
                // canvas would fill transparent mask pixels and hide the background.
                canvas.drawBitmap(bitmap, null,
                  RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()),
                  Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(color or Color.BLACK, PorterDuff.Mode.SRC_IN)
                  })
              } finally { bitmap.recycle() }
            }
            return result
          }
          return decode(if (dark) night else day)
        }
      }
    }
    fun compositeNativeLayers(paths: List<String>): Bitmap? {
      if (paths.isEmpty() || paths.size > NativeLayerConfig.MAX_LAYERS) return null
      if (paths.any { zip.getEntry(it) == null }) return null
      val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val bounds = NativeLayerViewport.bounds(Rect(0, 0, SIZE, SIZE))
        for (path in paths) {
          val bitmap = bytes(path)?.let {
            decode(it, bounds.width(), keepTexture = true)
          } ?: run { result.recycle(); return null }
          try { canvas.drawBitmap(bitmap, null, bounds, paint) } finally { bitmap.recycle() }
        }
        return result
      } catch (failure: Throwable) { result.recycle(); throw failure }
    }
    // A layer set must be complete in one appearance; never mix day and night layers.
    for (prefix in if (dark) listOf("nightmode/", "") else listOf("")) {
      val base = "${prefix}layer_animating_icons/$name/"
      bytes(base + "config.xml", 64 * 1024)?.let { config ->
        val layerConfig = try {
          // The legacy config only has numbered layer slots and may use one
          // implicit slot. Probe its known resource locations for this icon.
          val availableLayers = (0 until NativeLayerConfig.MAX_LAYERS)
            .map(Int::toString).filterTo(linkedSetOf()) { layer ->
              zip.getEntry("$base$layer/quiet/quietImage.png") != null ||
                zip.getEntry("$base$layer/fancy/manifest.xml") != null
            }
          NativeLayerConfig.parse(
            config,
            IconPackLimits(maxXmlBytes = 64 * 1024),
            check,
            availableLayers,
          )
        } catch (_: IconPackException) { null }
        if (layerConfig != null) {
          val weatherLayer = layerConfig.layers.last().takeIf { weather && it !in layerConfig.quietOnly }
          if (weatherLayer != null) {
            val fancy = "$base$weatherLayer/fancy/"
            val program = bytes(fancy + "manifest.xml", 16 * 1024)?.let {
              NativeWeatherLayerProgram.snapshot(it, fancy) { path -> zip.getEntry(path) != null }
            }
            if (program != null) {
              val art = fancy + if (dark && program.embeddedNight) {
                NativeWeatherLayerProgram.NIGHT_DIRECTORY
              } else ""
              val paths = layerConfig.layers.dropLast(1).map { layer ->
                val directory = "$base$layer/"
                val pair = try {
                  bytes(directory + "fancy/manifest.xml", 64 * 1024)?.let {
                    NativeMamlPair.parse(it, check)
                  }
                } catch (_: IconPackException) { null }
                if (pair != null) directory + "fancy/" + if (dark) pair.dark else pair.light
                else directory + "quiet/quietImage.png"
              }
              val result = compositeNativeLayers(
                if (program.background) paths + (art + "bg.png") else paths)
              if (result != null) {
                try {
                  val temperature = weatherTemperature(program.global)
                  if (temperature != null) {
                    drawWeatherTemperature(result, temperature, art) { path ->
                      bytes(path)?.let { decode(it, keepTexture = true) }
                    }
                  }
                  onDynamic()
                  return result
                } catch (failure: Throwable) {
                  result.recycle()
                  throw failure
                }
              }
            }
          }
          for (quiet in layerConfig.quietSnapshots(base) { zip.getEntry(it) != null }) {
            if (quiet.nativeLayers) compositeNativeLayers(quiet.paths)?.let { return it }
            else image(quiet.paths.single())?.let { return it }
          }
        }
      }
      for (bucket in DRAWABLE_BUCKETS) {
        compositeNativeLayers(listOf(0, 1).map { "${prefix}res/$bucket/$name/$it.png" })
          ?.let { return it }
        image("${prefix}res/$bucket/$name.png")?.let { return it }
      }
    }
    return null
  }

  private fun drawWeatherTemperature(
    result: Bitmap, temperature: Int, base: String, glyphImage: (String) -> Bitmap?,
  ) {
    val glyphs = mutableListOf<Bitmap>()
    try {
      for (character in "$temperature°") {
        glyphs += glyphImage("${base}num_$character.png") ?: return
      }
      val canvas = Canvas(result)
      val bounds = NativeLayerViewport.bounds(Rect(0, 0, SIZE, SIZE))
      val scale = bounds.width().toFloat() / NativeWeatherLayerProgram.CANVAS_SIZE
      canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
      canvas.scale(scale, scale)
      val paint = Paint(Paint.FILTER_BITMAP_FLAG)
      var x = NativeWeatherLayerProgram.TEMPERATURE_X
      val y = NativeWeatherLayerProgram.TEMPERATURE_Y
      for (glyph in glyphs) {
        canvas.drawBitmap(glyph, null, RectF(x, y, x + glyph.width, y + glyph.height), paint)
        x += glyph.width
      }
    } finally { glyphs.forEach { it.recycle() } }
  }

  private fun decode(bytes: ByteArray, size: Int = SIZE, keepTexture: Boolean = false): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
    var sample = 1
    while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) sample *= 2
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
      BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }) ?: return null
    return if (keepTexture || source.width == size && source.height == size) source else {
      try { Bitmap.createScaledBitmap(source, size, size, true) } finally { source.recycle() }
    }
  }

  private fun dynamic(
    manifest: ByteArray, dark: Boolean, locale: Locale, now: Long, check: () -> Unit,
    image: (String) -> Bitmap?,
  ): Bitmap {
    val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(result)
      canvas.scale(SIZE / 150f, SIZE / 150f)
      val date = Calendar.getInstance().apply { timeInMillis = now }
      BoundedPackXml.read(manifest, IconPackLimits(maxXmlBytes = 64 * 1024), check,
        start = { tag, a, _ ->
          val visible = when (a["visibility"]) {
            "#__darkmode" -> dark
            "1-#__darkmode" -> !dark
            "0" -> false
            else -> true
          }
          if (visible) when (tag) {
            "Image" -> {
              val bitmap = requireNotNull(image(a.getValue("src")))
              try {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                a["tint"]?.let {
                  paint.colorFilter = PorterDuffColorFilter(Color.parseColor(it), PorterDuff.Mode.MULTIPLY)
                }
                val hour = date.get(Calendar.HOUR).toFloat()
                val minute = date.get(Calendar.MINUTE).toFloat()
                val second = date.get(Calendar.SECOND).toFloat()
                val angle = when (a["angle"]) {
                  "#hour12*30+#minute/2" -> hour * 30f + minute / 2f
                  "#minute*6" -> minute * 6f
                  "#hour12*30+#minute/2+#second/120" -> hour * 30f + minute / 2f + second / 120f
                  "#minute*6+#second/10" -> minute * 6f + second / 10f
                  "#second*6" -> second * 6f
                  else -> 0f
                }
                val save = canvas.save()
                canvas.rotate(angle, 75f, 75f)
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, 150f, 150f), paint)
                canvas.restoreToCount(save)
              } finally { bitmap.recycle() }
            }
            "DateTime", "Text" -> {
              val text = if (tag == "Text") date.get(Calendar.DAY_OF_MONTH).toString()
                else SimpleDateFormat(a.getValue("format"), locale).format(Date(now))
              val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(a.getValue("color"))
                textSize = a.getValue("size").toFloat()
                typeface = Typeface.create(a["fontFamily"] ?: "sans-serif",
                  if (a["bold"] == "true") Typeface.BOLD else Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
              }
              canvas.drawText(text, a.getValue("x").toFloat(),
                a.getValue("y").toFloat() - paint.fontMetrics.ascent, paint)
            }
          }
        })
      return result
    } catch (failure: Throwable) { result.recycle(); throw failure }
  }
}
