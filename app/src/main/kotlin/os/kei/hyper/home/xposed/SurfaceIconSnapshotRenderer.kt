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
import java.util.zip.ZipFile
import os.kei.hyper.home.iconpack.BoundedPackXml
import os.kei.hyper.home.iconpack.IconPackLimits
import os.kei.hyper.home.iconpack.NativeAppleDynamicIcon
import os.kei.hyper.home.iconpack.NativeColorOsDynamicIcon
import os.kei.hyper.home.iconpack.NativeMamlPair
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.NativeLayerConfig
import os.kei.hyper.home.iconpack.validDottedName

/** Worker-only static snapshots. Foreign MAML is never interpreted or started. */
internal object SurfaceIconSnapshotRenderer {
  const val SIZE = 192
  private const val IMAGE_LIMIT = 4 * 1024 * 1024

  fun render(
    zip: ZipFile, name: String, dark: Boolean, locale: Locale, now: Long,
    palette: Long = 0L, check: () -> Unit = {}, packageName: String? = null,
    onDynamic: () -> Unit = {},
  ): Bitmap? {
    if (!validDottedName(name) || name.length > 240) return null
    fun bytes(path: String, limit: Int = IMAGE_LIMIT): ByteArray? {
      check()
      val entry = zip.getEntry(path) ?: return null
      require(!entry.isDirectory && entry.size in 0..limit.toLong())
      return zip.getInputStream(entry).use { input ->
        input.readNBytes(limit + 1).also { require(it.size <= limit) }
      }
    }
    fun image(path: String): Bitmap? = bytes(path)?.let(::decode)
    var root = "fancy_icons/$name/"
    var manifest = bytes(root + "manifest.xml", 64 * 1024)
    if (manifest == null && packageName != null && packageName != name) {
      root = "fancy_icons/$packageName/"
      manifest = bytes(root + "manifest.xml", 64 * 1024)
    }
    if (manifest != null) {
      // Older KeiHome calendars used this fixed accent/text pair. Authenticate the
      // entire template after that finite substitution, without interpreting foreign MAML.
      val legacyCalendar = NativeColorOsDynamicIcon.kindOfManifest(
        manifest.toString(Charsets.UTF_8).replace("#FF0A7FFB", "#FF1969FF")
          .replace("#FF000000", "#FF101010").toByteArray(Charsets.UTF_8),
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
        val verified = listOf(false, true).any { expanded -> listOf(false, true).any { scalar ->
          try {
            NativePaletteIcon.entries(root.removePrefix("fancy_icons/").removeSuffix("/"), day, night, mask, background,
              legacyExpanded = expanded, legacyScalarBinders = scalar)
              .getValue(root + "manifest.xml").contentEquals(manifest)
          } catch (_: IllegalArgumentException) { false }
        } }
        if (verified) {
          if (palette ushr 48 == 1L) {
            val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            NativeAdaptiveIconMask.load()?.pathForBounds(Rect(0, 0, SIZE, SIZE))?.let(canvas::clipPath)
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
    fun composite(paths: List<String>): Bitmap? {
      if (paths.isEmpty() || paths.size > NativeLayerConfig.MAX_LAYERS) return null
      if (paths.any { zip.getEntry(it) == null }) return null
      val result = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(result)
        NativeAdaptiveIconMask.load()?.pathForBounds(Rect(0, 0, SIZE, SIZE))?.let(canvas::clipPath)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (path in paths) {
          val bitmap = image(path) ?: run { result.recycle(); return null }
          try { canvas.drawBitmap(bitmap, 0f, 0f, paint) } finally { bitmap.recycle() }
        }
        return result
      } catch (failure: Throwable) { result.recycle(); throw failure }
    }
    // A layer set must be complete in one appearance; never mix day and night layers.
    for (prefix in if (dark) listOf("nightmode/", "") else listOf("")) {
      image("${prefix}res/drawable-xxhdpi/$name.png")?.let { return it }
      composite(listOf(0, 1).map { "${prefix}res/drawable-xxhdpi/$name/$it.png" })
        ?.let { return it }
      val base = "${prefix}layer_animating_icons/$name/"
      val config = bytes(base + "config.xml", 64 * 1024) ?: continue
      val layers = try {
        NativeLayerConfig.parse(config, IconPackLimits(maxXmlBytes = 64 * 1024), check).layers
      } catch (_: Exception) { continue }
      // Quiet assets are declared snapshots. Do not execute source layer animations.
      image(base + "quiet/quietImage.png")?.let { return it }
      composite(layers.map { base + it + "/quiet/quietImage.png" })?.let { return it }
    }
    return null
  }

  private fun decode(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
    var sample = 1
    while (bounds.outWidth / sample > SIZE * 2 || bounds.outHeight / sample > SIZE * 2) sample *= 2
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
      BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false }) ?: return null
    return if (source.width == SIZE && source.height == SIZE) source else {
      try { Bitmap.createScaledBitmap(source, SIZE, SIZE, true) } finally { source.recycle() }
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
                val angle = when {
                  a["src"]!!.contains("hour") -> hour * 30 + minute / 2 + second / 120
                  a["src"]!!.contains("minute") -> minute * 6 + second / 10
                  a["src"]!!.contains("second") -> second * 6
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
