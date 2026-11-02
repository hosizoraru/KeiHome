package os.kei.hyper.home.xposed

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.util.Pair
import android.util.TypedValue
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Runs inside the existing Shell splash worker; never changes app windows or task snapshots. */
internal class SplashBackgroundRuntime(
  private val xposed: XposedInterface,
  private val loader: ClassLoader,
  private val lifecycle: ThemeCompatibilityLifecycle,
  private val enabled: AtomicBoolean,
  private val replace: (Method, String, XposedInterface.Hooker) -> Unit,
) {
  private data class Request(val context: Context, val drawer: Any, val protectedSurface: Boolean)
  private val request = ThreadLocal<Request>()
  private val failureLogged = AtomicBoolean()
  private val appliedLogged = AtomicBoolean()

  fun install() {
    val drawer = Class.forName(DRAWER, false, loader)
    val info = Class.forName("android.window.StartingWindowInfo", false, loader)
    val attrs = Class.forName("$DRAWER\$SplashScreenWindowAttrs", false, loader)
    val activity = android.content.pm.ActivityInfo::class.java
    val make = drawer.getDeclaredMethod("makeSplashScreenContentView", Context::class.java,
      info, Int::class.javaPrimitiveType, java.util.function.Consumer::class.java)
    val background = drawer.getDeclaredMethod("getBGColorFromCache", activity,
      java.util.function.IntSupplier::class.java)
    val tmp = drawer.getDeclaredField("mTmpAttrs").apply { isAccessible = true }
    val icon = attrs.getDeclaredField("mSplashScreenIcon").apply { isAccessible = true }
    val brand = attrs.getDeclaredField("mBrandingImage").apply { isAccessible = true }
    val windowResource = attrs.getDeclaredField("mWindowBgResId").apply { isAccessible = true }
    val splashColor = attrs.getDeclaredField("mWindowBgColor").apply { isAccessible = true }
    val snapshot = info.getDeclaredField("mQSFileDescriptor").apply { isAccessible = true }
    val helper = Class.forName("android.app.TaskSnapshotHelperImpl", false, loader)
    val modify = helper.getDeclaredMethod("modifySplashIfNeeded", Int::class.javaPrimitiveType,
      Context::class.java, info, Int::class.javaPrimitiveType,
      android.graphics.drawable.Drawable::class.java, activity)
    check(make.returnType.name == "android.window.SplashScreenView" &&
      background.returnType == Int::class.javaPrimitiveType && modify.returnType == Pair::class.java)
    fun protected(current: Request): Boolean {
      if (current.protectedSurface) return true
      val values = tmp.get(current.drawer)
      if (icon.get(values) != null || brand.get(values) != null) return true
      val declaredColor = splashColor.getInt(values)
      if (declaredColor != 0 && !SplashBackgroundPolicy.isNeutral(declaredColor)) return true
      val resource = windowResource.getInt(values)
      if (resource != 0) {
        val value = TypedValue()
        current.context.resources.getValue(resource, value, true)
        if (value.type !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) return true
      }
      return false
    }
    replace(make, "$PREFIX.request", XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else {
        val previous = request.get()
        try {
          request.set(Request(chain.getArg(0) as Context, requireNotNull(chain.thisObject),
            (chain.getArg(2) as Int) == 4 || snapshot.get(chain.getArg(1)) != null))
          chain.proceed()
        } finally {
          if (previous == null) request.remove() else request.set(previous)
          lifecycle.leave()
        }
      }
    })
    replace(background, "$PREFIX.color", nativeResult { result ->
      val current = request.get() ?: return@nativeResult result
      if (result !is Int || protected(current)) result
      else adapt(current.context, result)
    })
    replace(modify, "$PREFIX.overlay", nativeResult { result ->
      val current = request.get() ?: return@nativeResult result
      if (result !is Pair<*, *> || protected(current)) return@nativeResult result
      // HyperOS type 6 overlays windowBackground on the modern splash color.
      // Only its solid-color result is eligible; bitmap/quick-start snapshots retain ownership.
      val drawable = result.second as? ColorDrawable ?: return@nativeResult result
      if (drawable.alpha != 255) return@nativeResult result
      val color = adapt(current.context, drawable.color)
      if (color == drawable.color) result else Pair(result.first, ColorDrawable(color))
    })
    xposed.deoptimize(make)
    xposed.log(Log.INFO, TAG, "Native Shell splash background profile ready; enabled=${enabled.get()}")
  }

  private fun nativeResult(action: (Any?) -> Any?) = XposedInterface.Hooker { chain ->
    if (!lifecycle.enter()) chain.proceed() else try {
      val original = chain.proceed()
      if (!enabled.get()) original else try { action(original) }
      catch (failure: Exception) {
        if (failureLogged.compareAndSet(false, true)) {
          xposed.log(Log.WARN, TAG, "Splash adaptation unavailable; keeping native result", failure)
        }
        original
      }
    } finally { lifecycle.leave() }
  }

  private fun adapt(context: Context, color: Int): Int {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
      Configuration.UI_MODE_NIGHT_YES
    val adapted = SplashBackgroundPolicy.adapt(color, night)
    if (adapted != color && appliedLogged.compareAndSet(false, true)) {
      xposed.log(Log.INFO, TAG,
        "Adapted neutral splash ${context.packageName}: ${color.toUInt().toString(16)} -> ${adapted.toUInt().toString(16)}")
    }
    return adapted
  }

  companion object {
    private const val DRAWER = "com.android.wm.shell.startingsurface.SplashscreenContentDrawer"
    private const val PREFIX = "keihome.systemui.splash"
    private const val TAG = "KeiHomeSplash"
  }
}
