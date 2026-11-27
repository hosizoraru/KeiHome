package os.kei.hyper.home.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CancellationException

/** Load installed resources directly, without querying the currently applied theme. */
internal object OriginalAppIcons {
  fun render(context: Context, target: IconPackComponent, sizePx: Int, dark: Boolean,
    check: () -> Unit = {}): Bitmap? {
    require(sizePx in 1..512)
    check()
    return try {
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
      val drawable = owner.resources.getDrawable(icon, owner.theme).mutate()
      check()
      val bitmap = rasterize(drawable, sizePx)
      try {
        IconMaskPolicy.clipIfExceeds(bitmap, OriginalIconShape.mask, check)
      } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
      }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: PackageManager.NameNotFoundException) { null
    } catch (_: android.content.res.Resources.NotFoundException) { null }
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
