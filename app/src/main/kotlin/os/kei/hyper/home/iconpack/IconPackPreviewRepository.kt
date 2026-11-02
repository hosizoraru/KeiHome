package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.util.TypedValue
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Shared snapshots: the UI must not mutate or recycle these bitmaps. Null is an unavailable
 * preview, never a reason to substitute a recolored or unrelated application icon. */
internal data class IconPackPreviews(
  val light: Bitmap? = null,
  val dark: Bitmap? = null,
  val lightFailure: String? = null,
  val darkFailure: String? = null,
)

internal class IconPackPreviewRepository(
  context: Context,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val application = context.applicationContext
  private val renderer = IconPackRenderer(application)
  private val worker = dispatcher.limitedParallelism(2)
  private val lock = Any()
  private var generation = 0L
  private val cache = object : LruCache<Key, IconPackPreviews>(4 * 1024 * 1024) {
    override fun sizeOf(key: Key, value: IconPackPreviews) =
      (value.light?.allocationByteCount ?: 0) + (value.dark?.allocationByteCount ?: 0)
  }

  suspend fun load(pack: IconPackDescriptor, entry: IconPackEntry, sizePx: Int = 128): IconPackPreviews {
    require(sizePx in 1..512)
    val requestedGeneration = synchronized(lock) { generation }
    return withContext(worker) {
      val caller = currentCoroutineContext()
      caller.ensureActive()
      val key = Key(pack.id, pack.source, entry.key, sizePx, Configuration(application.resources.configuration),
        application.resources.displayMetrics.densityDpi)
      synchronized(lock) { cache.get(key)?.let { return@withContext it } }
      fun render(dark: Boolean): Pair<Bitmap?, String?> = try {
        caller.ensureActive()
        val bitmap = renderer.open(pack, dark).use { it.render(entry, sizePx) { caller.ensureActive() } }
        bitmap to if (bitmap == null) "No static artwork for this mode" else null
      } catch (cancelled: CancellationException) { throw cancelled
      } catch (failure: Exception) { null to (failure.message ?: "Artwork cannot be previewed") }
      val (light, lightFailure) = render(false)
      val (dark, darkFailure) = render(true)
      val result = IconPackPreviews(light, dark, lightFailure, darkFailure)
      synchronized(lock) {
        caller.ensureActive()
        if (generation == requestedGeneration && light != null && dark != null) cache.put(key, result)
      }
      result
    }
  }

  fun sizeBytes(): Int = synchronized(lock) { cache.size() }
  fun clear() = synchronized(lock) { generation++; cache.evictAll() }

  private data class Key(val packId: String, val source: IconPackSource, val entry: String,
    val size: Int, val configuration: Configuration, val density: Int)
}

/** Used by preview and explicit static export, with one resource session for a batch. */
internal class IconPackRenderer(private val context: Context) {
  private val android = AndroidIconPackResources(context)

  fun open(pack: IconPackDescriptor, dark: Boolean): Session {
    val resources = when (val source = pack.source) {
      is IconPackSource.Installed -> {
        val info = context.packageManager.getPackageInfo(source.packageName, PackageManager.PackageInfoFlags.of(0))
        if (info.longVersionCode != source.versionCode || info.lastUpdateTime != source.updatedAt) {
          throw IconPackException("Icon pack changed; refresh the catalog")
        }
        android.installed(info.applicationInfo ?: throw IconPackException("Missing application resources"), dark)
      }
      is IconPackSource.Imported -> if (pack.format == IconPackFormat.APK) android.archive(source.record.originalFile, dark) else null
    }
    return Session(pack, dark, resources)
  }

  inner class Session(
    private val pack: IconPackDescriptor,
    private val dark: Boolean,
    private val android: AndroidIconPackResources.Session?,
  ) : Closeable {
    private val nativeMask by lazy(LazyThreadSafetyMode.NONE) { NativeAdaptiveIconMask.load() }

    /** Android's declared monochrome layer is independent of the pack's colored artwork. */
    fun themedLayers(entry: IconPackEntry, sizePx: Int, check: () -> Unit): Pair<Bitmap, Bitmap>? {
      val artwork = entry.artwork(dark) as? IconPackArtwork.Resource ?: return null
      val session = android ?: return null
      val id = session.drawableId(artwork.drawable)
      if (id == 0) return null
      validateDrawable(session.resources, id, DrawableBudget(), 0, check)
      val drawable = session.resources.getDrawable(id, null).mutate() as? AdaptiveIconDrawable ?: return null
      if (drawable.monochrome == null) return null
      drawable.setBounds(0, 0, sizePx, sizePx)
      val background = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      try {
        Canvas(background).drawPath(nativeMask?.pathFor(drawable) ?: drawable.iconMask,
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val foreground = ColorOsFallbackRenderer(context).rasterize(drawable, false, sizePx,
          mask = background, monochrome = true, check = check)
        return foreground to background
      } catch (failure: Throwable) {
        background.recycle()
        throw failure
      }
    }

    fun render(entry: IconPackEntry, sizePx: Int, check: () -> Unit = {}): Bitmap? {
      require(sizePx in 1..512)
      check()
      return when (val artwork = entry.artwork(dark)) {
        null -> null
        is IconPackArtwork.Resource -> {
          val session = android ?: throw IconPackException("Missing APK resource session")
          val id = session.drawableId(artwork.drawable)
          if (id == 0) return null
          val value = TypedValue()
          session.resources.getValue(id, value, true)
          if (rasterPath(value.string?.toString())) {
            bitmap({ session.resources.openRawResource(id) }, sizePx, check)
          } else {
            validateDrawable(session.resources, id, DrawableBudget(), 0, check)
            check()
            val drawable = session.resources.getDrawable(id, null).mutate()
            (drawable as? Animatable)?.stop()
            draw(drawable, sizePx, if (drawable is AdaptiveIconDrawable) nativeMask else null, check)
          }
        }
        is IconPackArtwork.Archive -> {
          val selected = selectArchiveVariant(artwork.variants, dark,
            context.resources.displayMetrics.densityDpi, Build.VERSION.SDK_INT) ?: return null
          if (!selected.isRaster) throw IconPackException("Uncompiled drawable XML requires an APK resource table")
          val record = (pack.source as? IconPackSource.Imported)?.record ?: throw IconPackException("Missing imported archive")
          val file = if (artwork.part == IconArchivePart.ICONS) record.iconsFile else record.originalFile
          val bytes = IconPackArchive.readEntry(file ?: throw IconPackException("Missing icons module"), selected.path, 16 * 1024 * 1024, check)
          bitmap({ bytes.inputStream() }, sizePx, check)
        }
      }
    }

    override fun close() { android?.close() }
  }

  private fun rasterPath(path: String?) = path?.substringAfterLast('.')?.lowercase() in setOf("png", "webp", "jpg", "jpeg")

  private fun bounds(input: () -> InputStream): BitmapFactory.Options = BitmapFactory.Options().also { options ->
    options.inJustDecodeBounds = true
    input().use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192 ||
      options.outWidth.toLong() * options.outHeight > 32L * 1024 * 1024
    ) throw IconPackException("Invalid or oversized raster artwork")
  }

  private fun bitmap(input: () -> InputStream, size: Int, check: () -> Unit): Bitmap {
    val dimensions = bounds(input)
    var sample = 1
    while (maxOf(dimensions.outWidth, dimensions.outHeight) / sample > size * 2) sample *= 2
    check()
    val decoded = input().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
      inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false
    }) } ?: throw IconPackException("Invalid raster artwork")
    try {
      check()
      val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      try {
        Canvas(output).drawBitmap(decoded, null, fit(decoded.width, decoded.height, size), Paint(Paint.FILTER_BITMAP_FLAG))
        return output
      } catch (failure: Exception) { output.recycle(); throw failure }
    } finally { decoded.recycle() }
  }

  private class DrawableBudget {
    val seen = mutableSetOf<Int>()
    val visiting = mutableSetOf<Int>()
    var pixels = 0L
    var events = 0
    var textBytes = 0L
  }

  /** Bound the whole referenced graph before framework inflation, including reference cycles. */
  private fun validateDrawable(resources: Resources, id: Int, budget: DrawableBudget, depth: Int, check: () -> Unit) {
    if (id in budget.visiting) throw IconPackException("Cyclic drawable reference")
    if (id in budget.seen) return
    if (depth > 12 || budget.seen.size >= 64) throw IconPackException("Drawable reference limit exceeded")
    budget.seen += id
    budget.visiting += id
    try {
      check()
      val value = TypedValue()
      resources.getValue(id, value, true)
      val path = value.string?.toString() ?: return
      if (rasterPath(path)) {
        val dimensions = bounds { resources.openRawResource(id) }
        // Nested bitmaps are inflated by Android without our thumbnail sampling.
        budget.pixels += dimensions.outWidth.toLong() * dimensions.outHeight
        if (budget.pixels > 4L * 1024 * 1024) throw IconPackException("Oversized nested drawable graph")
        return
      }
      if (!path.endsWith(".xml")) throw IconPackException("Unsupported drawable resource")
      resources.getXml(id).use { xml ->
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
          check()
          if (++budget.events > 8192 || xml.depth > 32) throw IconPackException("Drawable XML limit exceeded")
          if (xml.eventType == XmlPullParser.START_TAG) {
            if (xml.depth == 1 && xml.name !in STATIC_DRAWABLE_TAGS) throw IconPackException("Unsupported static drawable type")
            if (xml.name in setOf("animated-vector", "animated-selector", "animation-list", "drawable")) {
              throw IconPackException("Animated or custom drawable inflation is not supported")
            }
            if (xml.attributeCount > 64) throw IconPackException("Drawable attribute limit exceeded")
            for (i in 0 until xml.attributeCount) {
              if (xml.getAttributeName(i) == "class") throw IconPackException("Custom drawable classes are not loaded")
              budget.textBytes += xml.getAttributeValue(i).length
              if (budget.textBytes > 1024 * 1024) throw IconPackException("Drawable path limit exceeded")
              val reference = xml.getAttributeResourceValue(i, 0)
              if (reference != 0 && resources.getResourceTypeName(reference) in setOf("drawable", "mipmap")) {
                validateDrawable(resources, reference, budget, depth + 1, check)
              }
            }
          }
          xml.next()
        }
      }
    } finally { budget.visiting.remove(id) }
  }

  private fun draw(drawable: Drawable, size: Int, nativeMask: NativeAdaptiveIconMask?, check: () -> Unit): Bitmap {
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
      drawable.bounds = fit(drawable.intrinsicWidth.takeIf { it > 0 } ?: size,
        drawable.intrinsicHeight.takeIf { it > 0 } ?: size, size)
      val canvas = Canvas(output)
      nativeMask?.pathFor(drawable)?.let { canvas.clipPath(it) }
      drawable.draw(canvas)
      check()
      return output
    } catch (failure: Exception) { output.recycle(); throw failure }
  }

  private fun fit(width: Int, height: Int, size: Int): Rect {
    val scale = size.toDouble() / maxOf(width, height)
    val w = maxOf(1, (width * scale).toInt())
    val h = maxOf(1, (height * scale).toInt())
    return Rect((size - w) / 2, (size - h) / 2, (size - w) / 2 + w, (size - h) / 2 + h)
  }

  private companion object {
    val STATIC_DRAWABLE_TAGS = setOf("vector", "adaptive-icon", "bitmap", "inset", "layer-list",
      "selector", "shape", "clip", "scale", "rotate", "color", "ripple")
  }
}
