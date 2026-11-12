package os.kei.hyper.home.iconpack

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.coroutines.CancellationException

/** Load installed resources directly, without querying the currently applied theme. */
internal object OriginalAppIcons {
  fun render(context: Context, target: IconPackComponent, sizePx: Int, dark: Boolean,
    check: () -> Unit = {}): Bitmap? {
    require(sizePx in 1..512)
    check()
    return try {
      val configuration = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
          if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
      }
      val owner = context.createPackageContext(target.packageName, 0).createConfigurationContext(configuration)
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
      val previous = Rect(drawable.bounds)
      val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
      try {
        drawable.setBounds(0, 0, sizePx, sizePx)
        check()
        drawable.draw(Canvas(bitmap))
        IconMaskPolicy.clip(bitmap, OriginalIconShape.mask).also { if (it !== bitmap) bitmap.recycle() }
      } catch (failure: Throwable) {
        bitmap.recycle()
        throw failure
      } finally { drawable.bounds = previous }
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: PackageManager.NameNotFoundException) { null
    } catch (_: android.content.res.Resources.NotFoundException) { null }
  }
}
