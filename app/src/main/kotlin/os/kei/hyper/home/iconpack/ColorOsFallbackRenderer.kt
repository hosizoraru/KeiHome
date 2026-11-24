package os.kei.hyper.home.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.util.TypedValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import org.xmlpull.v1.XmlPullParser
import os.kei.hyper.home.xposed.MonochromeMaskQuality

/**
 * Resource-only CLASSIC app fallback, independently implemented from the contracts observed in
 * DefaultStyle, CustomAdaptiveIconConfig, AdaptiveIconDrawableExtImpl and DarkModeFilter.
 * Source oplus-framework.jar SHA-256:
 * 52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae.
 *
 * sizePx is the standard icon viewport (icon/max = foreground/max = 1). The source phone's
 * ux_icon_default_size is 60 dp; transparent flat artwork uses round(38 dp) / round(60 dp).
 * Adaptive children expand using the source's integer bounds, including odd-size rounding.
 * The software composition bitmap uses the source renderer's Display P3 color space.
 * The source IconEdgeExtenderLite.extend(Drawable, Resources) is a no-op.
 *
 * A supplied bitmap contributes ONLY alpha. Without one, this returns unmasked layer content:
 * partial reproduction, with no invented rounded rectangle or destination-device mask.
 * Custom source size/shape/G2 settings are outside this standard-viewport API.
 * Missing/unsafe resources and dynamic originals stay absent. No foreign application, drawable
 * class, launcher loadIcon hook, animation stop, or time/date snapshot is executed.
 *
 * Returned bitmaps belong to the caller. This class never recycles the supplied source mask.
 */
internal class ColorOsFallbackRenderer(
  private val context: Context,
  private val style: String = "classic",
  private val sources: Sources = Sources(context),
  private val onSkip: (IconPackComponent, SkipReason) -> Unit = { _, _ -> },
) {
  internal class Sources(private val context: Context) {
    private val apps = ColorOsGenerationCache<String, ApplicationInfo>(256)
    private val activities = ColorOsGenerationCache<IconPackComponent, ActivityInfo?>(256)
    private val resources = ColorOsGenerationCache<Pair<String, Boolean>, Resources>(16)

    fun app(packageName: String): ApplicationInfo = apps.get(packageName) {
      context.packageManager.getApplicationInfo(packageName,
        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
    }

    fun activity(target: IconPackComponent, load: () -> ActivityInfo): ActivityInfo =
      activities.get(target) {
        try { load() } catch (_: PackageManager.NameNotFoundException) { null }
      } ?: throw PackageManager.NameNotFoundException(target.flattened)

    fun resources(app: ApplicationInfo, configuration: Configuration, dark: Boolean): Resources =
      resources.get(app.packageName to dark) {
        context.packageManager.getResourcesForApplication(app, configuration)
      }
  }

  enum class SkipReason {
    MISSING_ACTIVITY, AMBIGUOUS_ACTIVITY, MISSING_ICON, RESOURCE_UNAVAILABLE,
    DYNAMIC_CLOCK, DYNAMIC_CALENDAR, ANIMATED_DRAWABLE, CUSTOM_DRAWABLE,
    UNSUPPORTED_RESOURCE, RESOURCE_LIMIT, INVALID_MASK, MISSING_MONOCHROME, UNUSABLE_MONOCHROME,
  }

  fun render(
    target: IconPackComponent, dark: Boolean, sizePx: Int, mask: Bitmap? = null,
    check: () -> Unit = {},
  ): Bitmap? = load(target, dark, sizePx, mask, false, check)

  fun renderExtra(
    icon: ExtraIcon, target: IconPackComponent, sizePx: Int,
    mask: NativeAdaptiveIconMask? = null, original: Boolean = false, check: () -> Unit = {},
  ): Bitmap? {
    if (!icon.accepts(target)) return null
    val resolved = target.copy(className = target.className ?: icon.activity)
    val flat = load(resolved, false, sizePx, null, false, check,
      extra = icon.takeUnless { original }, applicationIconOnly = icon.applicationIconOnly) ?: return null
    return try {
      IconMaskPolicy.clip(flat, mask).also { if (it !== flat) flat.recycle() }
    } catch (failure: Throwable) {
      flat.recycle()
      throw failure
    }
  }

  /** Only the original AdaptiveIconDrawable.monochrome; never a synthesized color silhouette. */
  fun monochrome(
    target: IconPackComponent, dark: Boolean, sizePx: Int, mask: Bitmap? = null,
    check: () -> Unit = {},
  ): Bitmap? = load(target, dark, sizePx, mask, true, check)

  /** Compare guarded original resources before OEM dimming; a dark filter is not night artwork. */
  fun hasNativeDayNight(target: IconPackComponent, check: () -> Unit = {}): Boolean {
    val light = load(target, false, 64, null, false, check, dimAtNight = false) ?: return false
    return try {
      val dark = load(target, true, 64, null, false, check, dimAtNight = false) ?: return false
      try { check(); !light.sameAs(dark) } finally { dark.recycle() }
    } finally { light.recycle() }
  }

  private fun load(
    target: IconPackComponent, dark: Boolean, sizePx: Int, mask: Bitmap?,
    monochrome: Boolean, check: () -> Unit, dimAtNight: Boolean = true, extra: ExtraIcon? = null,
    applicationIconOnly: Boolean = false,
  ): Bitmap? {
    require(sizePx in 1..512)
    check()
    val reason = try {
      val pm = context.packageManager
      val activity = if (applicationIconOnly) null else sources.activity(target) { activity(target, pm) }
      activity?.let { dynamicReason(target.packageName, it.metaData) }?.let { throw Unavailable(it) }
      // ActivityInfo.applicationInfo does not promise application-level GET_META_DATA contents.
      val app = sources.app(target.packageName)
      dynamicReason(target.packageName, app.metaData)?.let { throw Unavailable(it) }
      val id = activity?.iconResource ?: app.icon
      if (id == 0) throw Unavailable(SkipReason.MISSING_ICON)
      val configuration = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
          if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
      }
      val resources = sources.resources(activity?.applicationInfo ?: app, configuration, dark)
      validate(resources, id, ResourceBudget(), 0, check)
      check()
      val drawable = resources.getDrawable(id, null).mutate().apply {
        layoutDirection = configuration.layoutDirection
      }
      if (extra != null) return renderExtraLayer(drawable, extra, sizePx, check)
      return rasterize(drawable, dimAtNight && dark && style == "classic", sizePx, mask,
        resources.displayMetrics.density, monochrome, check)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (interrupted: InterruptedException) {
      throw interrupted
    } catch (unavailable: Unavailable) {
      unavailable.reason
    } catch (_: PackageManager.NameNotFoundException) {
      SkipReason.MISSING_ACTIVITY
    } catch (_: Exception) {
      SkipReason.RESOURCE_UNAVAILABLE
    }
    check()
    onSkip(target, reason)
    return null
  }

  private fun renderExtraLayer(
    drawable: Drawable, icon: ExtraIcon, size: Int, check: () -> Unit,
  ): Bitmap? {
    val adaptive = drawable as? AdaptiveIconDrawable
    val foreground = if (adaptive != null) adaptive.foreground ?: return null else drawable
    val artwork = icon.flatArtwork
    if (adaptive == null && artwork == null) return null
    visit(foreground, check) {
      if (it is Animatable) throw Unavailable(SkipReason.ANIMATED_DRAWABLE)
    }
    val output = createBitmap(size, size)
    try {
      val canvas = Canvas(output)
      val background = adaptive?.background
      val backgroundFilter = icon.adaptiveArtwork?.backgroundFilter
      if (backgroundFilter != null && background != null) {
        visit(background, check) {
          if (it is Animatable) throw Unavailable(SkipReason.ANIMATED_DRAWABLE)
        }
        background.colorFilter = backgroundFilter
        drawLayer(canvas, background, adaptiveBounds(size), false, check)
      } else {
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
          shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(),
            icon.top, icon.bottom, android.graphics.Shader.TileMode.CLAMP)
        })
      }
      if (adaptive != null && artwork?.isolatesAdaptiveForeground != true) {
        val foregroundFilter = icon.adaptiveArtwork?.foregroundFilter
        if (foregroundFilter != null) foreground.colorFilter = foregroundFilter
        else if (backgroundFilter == null || background == null) icon.tint?.let { foreground.setTint(it) }
        drawLayer(canvas, foreground, adaptiveBounds(size), false, check)
      } else {
        val layer = createBitmap(size, size)
        try {
          drawLayer(Canvas(layer), foreground, Rect(0, 0, size, size), false, check)
          if (!checkNotNull(artwork).isolate(layer, icon.tint, check)) {
            output.recycle()
            return null
          }
          val bounds = if (adaptive != null && artwork == ExtraFlatArtwork.WALLET_CARD) adaptiveBounds(size)
            else Rect(0, 0, size, size)
          canvas.drawBitmap(layer, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
        } finally { layer.recycle() }
      }
      return output
    } catch (failure: Throwable) {
      output.recycle()
      throw failure
    }
  }

  private fun activity(target: IconPackComponent, pm: PackageManager): ActivityInfo {
    val flags = PackageManager.GET_META_DATA.toLong()
    val className = target.className
    if (className != null) {
      return pm.getActivityInfo(ComponentName.createRelative(target.packageName, className),
        PackageManager.ComponentInfoFlags.of(flags))
    }
    val activities = pm.queryIntentActivities(
      Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(target.packageName),
      PackageManager.ResolveInfoFlags.of(flags),
    ).map { it.activityInfo }.distinctBy { it.name }
    if (activities.isEmpty()) throw Unavailable(SkipReason.MISSING_ACTIVITY)
    if (activities.size != 1) throw Unavailable(SkipReason.AMBIGUOUS_ACTIVITY)
    return activities.single()
  }

  private var suppliedMaskWarnings = 0

  /** Optional supplied alpha layers obey the same viewport and quality rules as APK monochrome. */
  internal fun monochromeLayer(
    target: IconPackComponent, layer: Drawable, sizePx: Int, check: () -> Unit,
  ): Bitmap? = try {
    rasterize(AdaptiveIconDrawable(null, null, layer), false, sizePx,
      monochrome = true, check = check)
  } catch (unavailable: Unavailable) {
    onSkip(target, unavailable.reason)
    if (suppliedMaskWarnings++ < 8)
      android.util.Log.w("KeiHomeMonochrome",
        "Ignoring optional supplied mask: $target, ${unavailable.reason}")
    null
  }

  /** Platform drawables only; exposed within the module for focused raster regression tests. */
  internal fun rasterize(
    drawable: Drawable, dark: Boolean, sizePx: Int, mask: Bitmap? = null,
    density: Float = context.resources.displayMetrics.density, monochrome: Boolean = false,
    check: () -> Unit = {},
  ): Bitmap {
    require(sizePx in 1..512 && density.isFinite() && density > 0)
    check()
    visit(drawable, check) {
      if (it is Animatable) throw Unavailable(SkipReason.ANIMATED_DRAWABLE)
    }
    if (mask != null && (mask.isRecycled || mask.width <= 0 || mask.height <= 0)) {
      throw Unavailable(SkipReason.INVALID_MASK)
    }
    val output = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888, true,
      ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
    try {
      val canvas = Canvas(output)
      val adaptive = drawable as? AdaptiveIconDrawable
      if (monochrome) {
        val mono = adaptive?.monochrome ?: throw Unavailable(SkipReason.MISSING_MONOCHROME)
        // APK monochrome layers use Android's viewport, independently of the ColorOS style.
        drawLayer(canvas, mono, NativeLayerViewport.bounds(Rect(0, 0, sizePx, sizePx)), false, check)
        val pixels = IntArray(sizePx * sizePx)
        output.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        var bitmapLayer = false
        visit(mono, check) { if (it is BitmapDrawable) bitmapLayer = true }
        if (MonochromeMaskQuality.isUnusable(pixels, sizePx, sizePx, bitmapLayer)) {
          throw Unavailable(SkipReason.UNUSABLE_MONOCHROME)
        }
        canvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC_IN)
      } else if (style == "nightshadow") {
        canvas.drawColor(Color.BLACK)
        val foreground = createBitmap(sizePx, sizePx)
        try {
          val layer = Canvas(foreground)
          drawLayer(layer, drawable, Rect(0, 0, sizePx, sizePx), dark, check)
          val circle = createBitmap(sizePx, sizePx)
          try {
            Canvas(circle).drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f,
              Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            applyMask(layer, circle, sizePx)
          } finally { circle.recycle() }
          val inset = sizePx * 0.125f
          canvas.drawBitmap(foreground, null,
            android.graphics.RectF(inset, inset, sizePx - inset, sizePx - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        } finally { foreground.recycle() }
      } else if (style == "material") {
        val extent = (sizePx * 0.8375f).toInt()
        val offset = (sizePx - extent) / 2
        val bounds = Rect(offset, offset, offset + extent, offset + extent)
        if (adaptive == null && hasTransparentEdges(drawable, check)) {
          drawLayer(canvas, 0xfffbfbfb.toInt().toDrawable(), bounds, dark, check)
          val fg = (sizePx * 38f * 1.25f / 60f).toInt()
          val start = (sizePx - fg) / 2
          drawLayer(canvas, drawable, Rect(start, start, start + fg, start + fg), dark, check)
        } else drawLayer(canvas, drawable, bounds, dark, check)
      } else if (adaptive != null) {
        if (adaptive.background == null && adaptive.foreground == null) {
          throw Unavailable(SkipReason.MISSING_ICON)
        }
        // The OEM adaptive viewport has an opaque black underpaint, even for missing layers.
        canvas.drawColor(Color.BLACK)
        val bounds = adaptiveBounds(sizePx)
        adaptive.background?.let { drawLayer(canvas, it, bounds, dark, check) }
        adaptive.foreground?.let { drawLayer(canvas, it, bounds, dark, check) }
      } else if (hasTransparentEdges(drawable, check)) {
        val background = 0xfffbfbfb.toInt().toDrawable()
        drawLayer(canvas, background, Rect(0, 0, sizePx, sizePx), dark, check)
        val maximum = (60f * density + 0.5f).toInt().coerceAtLeast(1)
        val foreground = (38f * density + 0.5f).toInt()
        val extent = ceil(sizePx * (foreground.toFloat() / maximum)).toInt()
        val offset = (sizePx - extent) / 2
        drawLayer(canvas, drawable, Rect(offset, offset, offset + extent, offset + extent), dark, check)
      } else {
        drawLayer(canvas, drawable, Rect(0, 0, sizePx, sizePx), dark, check)
      }
      check()
      if (mask != null) applyMask(canvas, mask, sizePx)
      check()
      return output
    } catch (failure: Throwable) {
      output.recycle()
      throw failure
    }
  }

  private fun drawLayer(
    canvas: Canvas, drawable: Drawable, bounds: Rect, dark: Boolean, check: () -> Unit,
  ) {
    check()
    val previousBounds = Rect(drawable.bounds)
    val filters = mutableListOf<Pair<Drawable, android.graphics.ColorFilter?>>()
    try {
      if (dark) {
        fun filter(layer: Drawable) {
          check()
          when (layer) {
            is AdaptiveIconDrawable -> {
              layer.background?.let(::filter); layer.foreground?.let(::filter)
            }
            is LayerDrawable -> for (i in 0 until layer.numberOfLayers) filter(layer.getDrawable(i))
            is DrawableWrapper -> layer.drawable?.let(::filter)
            is DrawableContainer -> layer.current?.takeIf { it !== layer }?.let(::filter)
            else -> if (!hasDeclaredTint(layer)) {
              filters += layer to layer.colorFilter
              layer.colorFilter = LightingColorFilter(0x00d6d6d6, 0)
            }
          }
        }
        filter(drawable)
      }
      drawable.bounds = bounds
      drawable.draw(canvas)
    } finally {
      drawable.bounds = previousBounds
      for ((layer, filter) in filters) layer.colorFilter = filter
    }
  }

  private fun hasDeclaredTint(drawable: Drawable): Boolean {
    val state = drawable.constantState ?: return false
    return try {
      state.javaClass.getDeclaredField("mTint").apply { isAccessible = true }.get(state) != null
    } catch (_: ReflectiveOperationException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }

  private fun hasTransparentEdges(drawable: Drawable, check: () -> Unit): Boolean {
    val original = (drawable as? BitmapDrawable)?.bitmap
    var owned: Bitmap? = null
    try {
      val bitmap = if (original != null && original.config != Bitmap.Config.HARDWARE) original else {
        if (original != null) original.copy(Bitmap.Config.ARGB_8888, false).also { owned = it }
        else {
          val width = drawable.intrinsicWidth.coerceAtLeast(1)
          val height = drawable.intrinsicHeight.coerceAtLeast(1)
          if (width.toLong() * height > MAX_PIXELS) throw Unavailable(SkipReason.RESOURCE_LIMIT)
          createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            owned = it
            drawLayer(Canvas(it), drawable, Rect(0, 0, width, height), false, check)
          }
        }
      } ?: throw Unavailable(SkipReason.RESOURCE_UNAVAILABLE)
      // No complete three-pixel edge samples exist on these tiny/missing intrinsic bounds.
      // Treat them as background artwork rather than inventing transparent padding.
      if (bitmap.width < 5 || bitmap.height < 5) return false
      var transparent = 0
      val stepX = ceil(bitmap.width / 4f).toInt()
      val stepY = ceil(bitmap.height / 4f).toInt()
      fun group(xs: IntArray, ys: IntArray) {
        check()
        if (xs.indices.count { Color.alpha(bitmap[xs[it], ys[it]]) < 220 } >= 2) transparent++
      }
      for (x in stepX until bitmap.width step stepX) {
        group(intArrayOf(x, x, x), intArrayOf(1, 2, 3))
        group(intArrayOf(x, x, x), intArrayOf(bitmap.height - 2, bitmap.height - 3, bitmap.height - 4))
      }
      for (y in stepY until bitmap.height step stepY) {
        group(intArrayOf(1, 2, 3), intArrayOf(y, y, y))
        group(intArrayOf(bitmap.width - 2, bitmap.width - 3, bitmap.width - 4), intArrayOf(y, y, y))
      }
      return transparent >= 6
    } finally {
      owned?.recycle()
    }
  }

  private fun applyMask(canvas: Canvas, mask: Bitmap, sizePx: Int) {
    val software = if (mask.config == Bitmap.Config.HARDWARE) {
      mask.copy(Bitmap.Config.ARGB_8888, false) ?: throw Unavailable(SkipReason.INVALID_MASK)
    } else mask
    try {
      canvas.drawBitmap(software, null, Rect(0, 0, sizePx, sizePx), Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
      })
    } finally {
      if (software !== mask) software.recycle()
    }
  }

  private class ResourceBudget {
    val active = mutableSetOf<Int>()
    val seen = mutableSetOf<Int>()
    var events = 0
    var pixels = 0L
  }

  /** Check every nested tag/reference before Resources can instantiate a custom drawable. */
  private fun validate(
    resources: Resources, id: Int, budget: ResourceBudget, depth: Int, check: () -> Unit,
  ) {
    check()
    if (id in budget.active || depth > 16 || budget.seen.size >= 128) {
      throw Unavailable(SkipReason.RESOURCE_LIMIT)
    }
    if (!budget.seen.add(id)) return
    budget.active += id
    try {
      val value = TypedValue()
      resources.getValue(id, value, true)
      val path = value.string?.toString() ?: return
      if (!path.endsWith(".xml")) {
        resources.openRawResource(id).use { input ->
          val header = input.readNBytes(21)
          val gif = header.size >= 6 && String(header, 0, 3, Charsets.US_ASCII) == "GIF"
          val animatedWebp = header.size == 21 &&
            String(header, 8, 8, Charsets.US_ASCII) == "WEBPVP8X" && header[20].toInt() and 2 != 0
          if (gif || animatedWebp) throw Unavailable(SkipReason.ANIMATED_DRAWABLE)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resources.openRawResource(id).use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw Unavailable(SkipReason.UNSUPPORTED_RESOURCE)
        budget.pixels += options.outWidth.toLong() * options.outHeight
        if (budget.pixels > MAX_PIXELS) throw Unavailable(SkipReason.RESOURCE_LIMIT)
        return
      }
      resources.getXml(id).use { xml ->
        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
          check()
          if (++budget.events > 16384 || xml.depth > 32 || xml.attributeCount > 64) {
            throw Unavailable(SkipReason.RESOURCE_LIMIT)
          }
          if (xml.eventType == XmlPullParser.START_TAG) {
            if (xml.name in ANIMATED_TAGS) throw Unavailable(SkipReason.ANIMATED_DRAWABLE)
            if (xml.name !in STATIC_TAGS) throw Unavailable(SkipReason.CUSTOM_DRAWABLE)
            for (i in 0 until xml.attributeCount) {
              if (xml.getAttributeName(i) == "class") throw Unavailable(SkipReason.CUSTOM_DRAWABLE)
              val reference = xml.getAttributeResourceValue(i, 0)
              if (reference != 0 && resources.getResourceTypeName(reference) in setOf("drawable", "mipmap", "color")) {
                validate(resources, reference, budget, depth + 1, check)
              }
            }
          }
          xml.next()
        }
      }
    } finally {
      budget.active.remove(id)
    }
  }

  private fun visit(drawable: Drawable, check: () -> Unit, depth: Int = 0, action: (Drawable) -> Unit) {
    check()
    if (depth > 32) throw Unavailable(SkipReason.RESOURCE_LIMIT)
    action(drawable)
    val children = when (drawable) {
      is AdaptiveIconDrawable -> listOfNotNull(drawable.background, drawable.foreground, drawable.monochrome)
      is LayerDrawable -> List(drawable.numberOfLayers) { drawable.getDrawable(it) }
      is DrawableWrapper -> listOfNotNull(drawable.drawable)
      is DrawableContainer -> listOfNotNull(drawable.current?.takeIf { it !== drawable })
      else -> emptyList()
    }
    for (child in children) visit(child, check, depth + 1, action)
  }

  private class Unavailable(val reason: SkipReason) : RuntimeException()

  internal companion object {
    private const val MAX_PIXELS = 4L * 1024 * 1024
    private val ANIMATED_TAGS = setOf("animated-vector", "animated-selector", "animated-rotate",
      "animated-image", "animation-list", "transition")
    private val STATIC_TAGS = setOf("adaptive-icon", "background", "foreground", "monochrome",
      "vector", "group", "path", "clip-path", "bitmap", "nine-patch", "inset", "layer-list",
      "item", "selector", "level-list", "shape", "size", "corners", "gradient", "solid",
      "stroke", "padding", "clip", "scale", "rotate", "color", "ripple")
    private val CLOCK_PACKAGES = setOf("com.android.deskclock", "com.google.android.deskclock",
      "com.coloros.alarmclock", "com.oplus.alarmclock", "com.oneplus.deskclock", "com.sec.android.app.clockpackage")
    private val CALENDAR_PACKAGES = setOf("com.android.calendar", "com.google.android.calendar",
      "com.coloros.calendar", "com.oplus.calendar", "com.samsung.android.calendar")

    internal fun dynamicReason(packageName: String, metadata: Bundle?): SkipReason? {
      if (packageName in CLOCK_PACKAGES) return SkipReason.DYNAMIC_CLOCK
      if (packageName in CALENDAR_PACKAGES) return SkipReason.DYNAMIC_CALENDAR
      for (key in metadata?.keySet().orEmpty()) {
        if (key.endsWith(".LEVEL_PER_TICK_ICON") || key.endsWith(".LEVEL_PER_TICK_ICON_ROUND")) {
          return SkipReason.DYNAMIC_CLOCK
        }
        if (key.endsWith(".dynamic_icons")) return SkipReason.DYNAMIC_CALENDAR
      }
      return null
    }

    internal fun adaptiveBounds(sizePx: Int): Rect {
      val center = sizePx / 2
      val halfExtent = (sizePx / 1.3333334f).toInt()
      return Rect(center - halfExtent, center - halfExtent, center + halfExtent, center + halfExtent)
    }
  }
}
