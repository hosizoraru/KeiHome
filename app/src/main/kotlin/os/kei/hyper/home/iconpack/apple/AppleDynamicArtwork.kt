package os.kei.hyper.home.iconpack.apple

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import os.kei.hyper.home.iconpack.NativeAppleDynamicIcon

/** Native Apple raster layers, with no runtime download, font bundle, or private API. */
internal class AppleDynamicArtwork(private val context: Context) {
  private val manifest by lazy {
    JSONObject(context.assets.open("apple-dynamic-icons/provenance.json")
      .use { it.readBytes().toString(Charsets.UTF_8) })
  }
  val revision: String by lazy {
    AppleIconPolicy.digest("apple-dynamic-v1".toByteArray() +
      context.assets.open("apple-dynamic-icons/provenance.json").use { it.readBytes() })
  }
  private val compressed = object : LruCache<String, ByteArray>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray) = value.size
  }

  fun assets(kind: NativeAppleDynamicIcon.Kind, options: AppleIconOptions): Map<String, ByteArray> {
    require(options.platform != ApplePlatform.MACOS)
    // Four full-canvas clock textures at 512px are already larger than a normal
    // launcher cell. Higher static-preview resolutions must not inflate live GPU textures.
    val size = options.resolution.coerceAtMost(512)
    val result = linkedMapOf<String, ByteArray>()
    for (dark in listOf(false, true)) {
      val suffix = if (dark) "night" else "day"
      result["$suffix.png"] = withBitmap(kind, "base", dark, options.platform, size) { png(it) }
      if (kind == NativeAppleDynamicIcon.Kind.CLOCK) {
        for (hand in listOf("hour", "minute", "second")) {
          result["${if (dark) "night_" else ""}$hand.png"] =
            withBitmap(kind, hand, dark, options.platform, size) { png(it) }
        }
      }
    }
    return result
  }

  fun snapshot(kind: NativeAppleDynamicIcon.Kind, options: AppleIconOptions,
    timeMillis: Long = System.currentTimeMillis(),
    locale: Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault()): Pair<ByteArray, ByteArray> {
    require(options.platform != ApplePlatform.MACOS)
    val calendar = Calendar.getInstance(timeZone, locale).apply { this.timeInMillis = timeMillis }
    fun draw(dark: Boolean): ByteArray {
      val size = options.resolution
      val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        withBitmap(kind, "base", dark, options.platform, size) { canvas.drawBitmap(it, 0f, 0f, paint) }
        if (kind == NativeAppleDynamicIcon.Kind.CLOCK) {
          val second = calendar.get(Calendar.SECOND)
          val minute = calendar.get(Calendar.MINUTE)
          val hour = calendar.get(Calendar.HOUR)
          val angles = listOf(hour * 30f + minute / 2f + second / 120f,
            minute * 6f + second / 10f, second * 6f)
          for ((index, hand) in listOf("hour", "minute", "second").withIndex()) {
            canvas.save()
            canvas.rotate(angles[index], size / 2f, size / 2f)
            withBitmap(kind, hand, dark, options.platform, size) { canvas.drawBitmap(it, 0f, 0f, paint) }
            canvas.restore()
          }
        } else {
          val scale = size / 150f
          paint.textAlign = Paint.Align.CENTER
          paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
          paint.textSize = 20f * scale
          paint.color = Color.rgb(255, 59, 48)
          val weekday = SimpleDateFormat("EEEE", locale).apply { this.timeZone = timeZone }.format(calendar.time)
          canvas.drawText(weekday, size / 2f, 20f * scale - paint.fontMetrics.top, paint)
          paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
          paint.textSize = 76f * scale
          paint.color = if (dark) Color.WHITE else Color.BLACK
          val date = SimpleDateFormat("d", locale).apply { this.timeZone = timeZone }.format(calendar.time)
          canvas.drawText(date, size / 2f, 43f * scale - paint.fontMetrics.top, paint)
        }
        return png(bitmap)
      } finally { bitmap.recycle() }
    }
    return draw(false) to draw(true)
  }

  private inline fun <T> withBitmap(kind: NativeAppleDynamicIcon.Kind, part: String, dark: Boolean,
    platform: ApplePlatform, size: Int, block: (Bitmap) -> T): T {
    val family = if (platform == ApplePlatform.IPADOS) "ipados" else "ios"
    val key = "$family.${kind.name.lowercase(Locale.ROOT)}.$part.${if (dark) "dark" else "light"}"
    val record = manifest.getJSONObject("images").getJSONObject(key)
    val path = record.getString("path")
    require(path.matches(Regex("images/[0-9a-f]{64}\\.webp")))
    val data = synchronized(compressed) { compressed.get(path) } ?: context.assets
      .open("apple-dynamic-icons/$path").use { it.readBytes() }.also {
        require(it.size in 1..2 * 1024 * 1024 && AppleIconPolicy.digest(it) == record.getString("sha256"))
        synchronized(compressed) { compressed.put(path, it) }
      }
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(data, 0, data.size,
      BitmapFactory.Options().apply { inSampleSize = 1024 / size; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    try {
      require(bitmap.width == size && bitmap.height == size)
      return block(bitmap)
    } finally { bitmap.recycle() }
  }

  private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    it.toByteArray()
  }

  companion object {
    fun kind(app: AppleAppMatch, options: AppleIconOptions): NativeAppleDynamicIcon.Kind? =
      if (options.platform == ApplePlatform.MACOS) null else when (app.id) {
        "1108185179" -> NativeAppleDynamicIcon.Kind.CALENDAR
        "1584215688" -> NativeAppleDynamicIcon.Kind.CLOCK
        else -> null
      }
  }
}
