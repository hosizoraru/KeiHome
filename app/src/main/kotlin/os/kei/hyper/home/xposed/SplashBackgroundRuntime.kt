package os.kei.hyper.home.xposed

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.util.Pair
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
  private val reportFailure: (String, Throwable) -> Unit,
) {
  private data class Request(
    val context: Context,
    val protectedSurface: Boolean,
    var attributes: Any? = null,
    var backgroundProtected: Boolean? = null,
  )
  private val request = ThreadLocal<Request>()
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
    val estimate = drawer.getDeclaredMethod("estimateTaskBackgroundColor", Context::class.java)
    val readAttrs = drawer.getDeclaredMethod("getWindowAttrs", Context::class.java, attrs)
    val windowResource = attrs.getDeclaredField("mWindowBgResId").apply { isAccessible = true }
    val splashColor = attrs.getDeclaredField("mWindowBgColor").apply { isAccessible = true }
    val snapshot = info.getDeclaredField("mQSFileDescriptor").apply { isAccessible = true }
    val helper = Class.forName("android.app.TaskSnapshotHelperImpl", false, loader)
    val modify = helper.getDeclaredMethod("modifySplashIfNeeded", Int::class.javaPrimitiveType,
      Context::class.java, info, Int::class.javaPrimitiveType,
      android.graphics.drawable.Drawable::class.java, activity)
    check(make.returnType.name == "android.window.SplashScreenView" &&
      background.returnType == Int::class.javaPrimitiveType &&
      estimate.returnType == Int::class.javaPrimitiveType &&
      readAttrs.returnType == Void.TYPE && modify.returnType == Pair::class.java)
    fun protected(current: Request): Boolean {
      if (current.protectedSurface) return true
      val values = current.attributes ?: return true
      current.backgroundProtected?.let { return it }
      val declaredColor = splashColor.getInt(values)
      val resource = windowResource.getInt(values)
      val protected = (declaredColor != 0 && !SplashBackgroundPolicy.isNeutral(declaredColor)) ||
        (resource != 0 &&
          SplashBackgroundDrawable.backgroundColor(current.context.getDrawable(resource)) == null)
      current.backgroundProtected = protected
      return protected
    }
    val requestGuard = guard("request")
    replace(make, "$PREFIX.request", XposedInterface.Hooker { chain ->
      if (!enabled.get() || !lifecycle.enter()) chain.proceed() else {
        val previous = request.get()
        try {
          val current = requestGuard.run<Request?>(null) {
            Request(chain.getArg(0) as Context,
              (chain.getArg(2) as Int) == 4 || snapshot.get(chain.getArg(1)) != null)
          }
          if (current == null) request.remove() else request.set(current)
          chain.proceed()
        } finally {
          if (previous == null) request.remove() else request.set(previous)
          lifecycle.leave()
        }
      }
    })
    val attrsGuard = guard("attributes")
    replace(readAttrs, "$PREFIX.attributes", XposedInterface.Hooker { chain ->
      val current = request.get()
      if (current == null || !enabled.get() || !lifecycle.enter()) chain.proceed() else try {
        val original = chain.proceed()
        attrsGuard.run(Unit) {
          current.attributes = chain.getArg(1)
          current.backgroundProtected = null
        }
        original
      } finally { lifecycle.leave() }
    })
    val estimateGuard = guard("estimate")
    replace(estimate, "$PREFIX.estimate", XposedInterface.Hooker { chain ->
      if (!enabled.get() || !lifecycle.enter()) chain.proceed() else {
        val previous = request.get()
        try {
          val current = Request(chain.getArg(0) as Context, false)
          request.set(current)
          val original = chain.proceed()
          estimateGuard.run(original) {
            if (original is Int && !protected(current)) adapt(current.context, original)
            else original
          }
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
      // Clone only a plain backing layer; preserve the artwork above it.
      val drawable = result.second as? android.graphics.drawable.Drawable ?: return@nativeResult result
      val night = isDarkMode(current.context)
      val adapted = SplashBackgroundDrawable.adapt(drawable, night)
      if (adapted === drawable) result else Pair(result.first, adapted)
    })
    xposed.deoptimize(make)
    xposed.deoptimize(estimate)
    xposed.log(Log.INFO, TAG, "Native Shell splash background profile ready; enabled=${enabled.get()}")
  }

  private fun nativeResult(action: (Any?) -> Any?): XposedInterface.Hooker {
    val guard = guard("color")
    return XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        val original = chain.proceed()
        if (!enabled.get()) original else guard.run(original) { action(original) }
      } finally { lifecycle.leave() }
    }
  }

  private fun guard(feature: String) = SystemUiHookGuard { failure ->
    reportFailure("splash $feature", failure)
  }

  private fun adapt(context: Context, color: Int): Int {
    val night = isDarkMode(context)
    val adapted = SplashBackgroundPolicy.adapt(color, night)
    if (adapted != color && appliedLogged.compareAndSet(false, true)) {
      Log.i(TAG,
        "Adapted neutral splash ${context.packageName}: ${color.toUInt().toString(16)} -> ${adapted.toUInt().toString(16)}")
    }
    return adapted
  }

  private fun isDarkMode(context: Context): Boolean =
    when ((context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode) {
      UiModeManager.MODE_NIGHT_YES -> true
      UiModeManager.MODE_NIGHT_NO -> false
      else -> {
        val global = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        (if (global == Configuration.UI_MODE_NIGHT_UNDEFINED)
          context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        else global) == Configuration.UI_MODE_NIGHT_YES
      }
    }

  companion object {
    private const val DRAWER = "com.android.wm.shell.startingsurface.SplashscreenContentDrawer"
    private const val PREFIX = "keihome.systemui.splash"
    private const val TAG = "KeiHomeSplash"
  }
}
