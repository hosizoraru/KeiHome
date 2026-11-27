package os.kei.hyper.home.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CancellationException
import os.kei.hyper.home.xposed.MonochromeMaskQuality

/** Load installed resources directly, without querying the currently applied theme. */
internal object OriginalAppIcons {
  fun render(context: Context, target: IconPackComponent, sizePx: Int, dark: Boolean,
    check: () -> Unit = {}): Bitmap? =
    render(context, target, sizePx, dark, OriginalIconShape.mask, check)

  fun render(context: Context, target: IconPackComponent, sizePx: Int, dark: Boolean,
    mask: NativeAdaptiveIconMask?, check: () -> Unit = {}): Bitmap? {
    require(sizePx in 1..512)
    check()
    return try {
      val drawable = loadDrawable(context, target, dark) ?: return null
      if (drawable is Animatable) return null
      check()
      val bitmap = rasterize(drawable, sizePx)
      try {
        IconMaskPolicy.clipIfExceeds(bitmap, mask, check)
      } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
      }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: PackageManager.NameNotFoundException) { null
    } catch (_: android.content.res.Resources.NotFoundException) { null }
  }

  /**
   * Renders an app's declared Android adaptive `<monochrome>` layer as a white alpha mask
   * at Android's adaptive child viewport, rejecting empty or solid-bitmap unusable masks.
   */
  fun renderMonochromeMask(
    context: Context,
    target: IconPackComponent,
    sizePx: Int,
    dark: Boolean = false,
    check: () -> Unit = {},
  ): Bitmap? {
    require(sizePx in 1..512)
    check()
    return try {
      val drawable = loadDrawable(context, target, dark) as? AdaptiveIconDrawable ?: return null
      rasterizeMonochromeMask(drawable.monochrome, sizePx, check)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: PackageManager.NameNotFoundException) { null
    } catch (_: android.content.res.Resources.NotFoundException) { null }
  }

  /** Renders an app's adaptive foreground layer alone (clipped to [mask]) for layered extraction. */
  fun renderAdaptiveForeground(
    context: Context,
    target: IconPackComponent,
    sizePx: Int,
    dark: Boolean = false,
    mask: NativeAdaptiveIconMask? = OriginalIconShape.mask,
    check: () -> Unit = {},
  ): Bitmap? {
    require(sizePx in 1..512)
    check()
    return try {
      val adaptive = loadDrawable(context, target, dark) as? AdaptiveIconDrawable ?: return null
      val fg = adaptive.foreground ?: return null
      if (fg is Animatable) return null
      check()
      val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      try {
        val previous = Rect(fg.bounds)
        try {
          fg.bounds = NativeLayerViewport.bounds(Rect(0, 0, sizePx, sizePx))
          fg.draw(Canvas(bitmap))
        } finally { fg.bounds = previous }
        IconMaskPolicy.clip(bitmap, mask).also { if (it !== bitmap) bitmap.recycle() }
      } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
      }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: PackageManager.NameNotFoundException) { null
    } catch (_: android.content.res.Resources.NotFoundException) { null }
  }

  /** Renders an app's usable official `<monochrome>` layer tinted with a committed Monet palette. */
  fun renderTintedMonochrome(
    context: Context,
    target: IconPackComponent,
    sizePx: Int,
    dark: Boolean,
    palette: Long,
    mask: NativeAdaptiveIconMask? = OriginalIconShape.mask,
    check: () -> Unit = {},
  ): Bitmap? {
    if (palette ushr 48 != 1L) return null
    val mono = renderMonochromeMask(context, target, sizePx, dark, check) ?: return null
    return try {
      tintMonochromeMask(mono, sizePx, palette, mask)
    } finally { mono.recycle() }
  }

  /** ActivityInfo variant for Shell splash windows. */
  fun renderLaunchTintedMonochrome(
    context: Context,
    activity: ActivityInfo,
    sizePx: Int,
    dark: Boolean,
    palette: Long,
    mask: NativeAdaptiveIconMask? = OriginalIconShape.mask,
  ): Bitmap? {
    if (palette ushr 48 != 1L) return null
    val icon = activity.iconResource
    if (icon == 0) return null
    val mono = try {
      val packageContext = if (context.packageName == activity.packageName) context
        else context.createPackageContext(activity.packageName, 0)
      val owner = withAppearance(packageContext, dark)
      if (owner !== context) owner.setTheme(activity.themeResource)
      val drawable = owner.getDrawable(icon)?.mutate() as? AdaptiveIconDrawable ?: return null
      rasterizeMonochromeMask(drawable.monochrome, sizePx) {}
    } catch (_: PackageManager.NameNotFoundException) { null }
    catch (_: android.content.res.Resources.NotFoundException) { null } ?: return null
    return try {
      tintMonochromeMask(mono, sizePx, palette, mask)
    } finally { mono.recycle() }
  }

  private fun loadDrawable(context: Context, target: IconPackComponent, dark: Boolean): Drawable? {
    val owner = withAppearance(context.createPackageContext(target.packageName, 0), dark)
    val pm = context.packageManager
    val app = pm.getApplicationInfo(target.packageName, PackageManager.ApplicationInfoFlags.of(0))
    val activity = target.className ?: pm.getLaunchIntentForPackage(target.packageName)?.component?.className
    val icon = activity?.let { name ->
      try { pm.getActivityInfo(ComponentName(target.packageName, name),
        PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())).icon
      } catch (_: PackageManager.NameNotFoundException) { null }
    }?.takeIf { it != 0 } ?: app.icon
    if (icon == 0) return null
    return owner.resources.getDrawable(icon, owner.theme).mutate()
  }

  private fun rasterizeMonochromeMask(mono: Drawable?, sizePx: Int, check: () -> Unit): Bitmap? {
    if (mono == null || mono is Animatable) return null
    check()
    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    try {
      val previous = Rect(mono.bounds)
      try {
        mono.bounds = NativeLayerViewport.bounds(Rect(0, 0, sizePx, sizePx))
        mono.draw(Canvas(output))
      } finally { mono.bounds = previous }
      check()
      val pixels = IntArray(sizePx * sizePx)
      output.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
      val isBitmap = MonochromeMaskQuality.containsBitmapDrawable(mono)
      if (MonochromeMaskQuality.isUnusable(pixels, sizePx, sizePx, isBitmap)) {
        output.recycle()
        return null
      }
      Canvas(output).drawColor(Color.WHITE, PorterDuff.Mode.SRC_IN)
      return output
    } catch (failure: Throwable) {
      output.recycle()
      throw failure
    }
  }

  private fun tintMonochromeMask(
    mono: Bitmap, sizePx: Int, palette: Long, mask: NativeAdaptiveIconMask?,
  ): Bitmap {
    val bg = palette.toInt() or Color.BLACK
    val fg = (palette ushr 24).toInt() or Color.BLACK
    val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(output)
      canvas.drawColor(bg)
      canvas.drawBitmap(mono, null, Rect(0, 0, sizePx, sizePx),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
          colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
        })
      return IconMaskPolicy.clip(output, mask).also { if (it !== output) output.recycle() }
    } catch (failure: Throwable) {
      output.recycle()
      throw failure
    }
  }

  /** Shell supplies the resolved activity and the context for its starting window. */
  fun renderLaunchIcon(
    context: Context,
    activity: ActivityInfo,
    sizePx: Int,
    dark: Boolean,
    mask: NativeAdaptiveIconMask? = OriginalIconShape.mask,
  ): Bitmap? {
    val icon = activity.iconResource
    if (icon == 0) return null
    return try {
      val packageContext = if (context.packageName == activity.packageName) context
        else context.createPackageContext(activity.packageName, 0)
      val owner = withAppearance(packageContext, dark)
      if (owner !== context) owner.setTheme(activity.themeResource)
      val drawable = owner.getDrawable(icon)?.mutate() ?: return null
      if (drawable is Animatable) null else rasterizeLaunchIcon(drawable, sizePx, mask)
    } catch (_: PackageManager.NameNotFoundException) { null }
    catch (_: android.content.res.Resources.NotFoundException) { null }
  }

  internal fun rasterizeLaunchIcon(
    drawable: Drawable,
    sizePx: Int,
    mask: NativeAdaptiveIconMask?,
  ): Bitmap {
    val bitmap = rasterize(drawable, sizePx)
    return try {
      IconMaskPolicy.clipIfExceeds(bitmap, mask)
    } catch (failure: Throwable) {
      bitmap.recycle()
      throw failure
    }
  }

  private fun withAppearance(context: Context, dark: Boolean): Context {
    val current = context.resources.configuration
    val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    if ((current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == night) return context
    return context.createConfigurationContext(Configuration(current).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    })
  }

  internal fun rasterize(drawable: Drawable, sizePx: Int): Bitmap {
    require(sizePx in 1..512)
    val previous = Rect(drawable.bounds)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(bitmap)
      val width = drawable.intrinsicWidth
      val height = drawable.intrinsicHeight
      if (drawable !is AdaptiveIconDrawable && width > 0 && height > 0) {
        // Fixed-size layers resolve against the authored viewport before output scaling.
        val scale = sizePx.toFloat() / maxOf(width, height)
        canvas.translate((sizePx - width * scale) / 2, (sizePx - height * scale) / 2)
        canvas.scale(scale, scale)
        drawable.setBounds(0, 0, width, height)
      } else {
        drawable.setBounds(0, 0, sizePx, sizePx)
      }
      drawable.draw(canvas)
      return bitmap
    } catch (failure: Throwable) {
      bitmap.recycle()
      throw failure
    } finally {
      drawable.bounds = previous
    }
  }
}
