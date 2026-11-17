package os.kei.hyper.home.xposed

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.provider.Settings
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.iconpack.NativePaletteIcon
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Runs on Shell's splash worker; keeps the system's entrance/exit ownership. */
internal class SplashIconFallbackRuntime(
  private val xposed: XposedInterface,
  private val loader: ClassLoader,
  private val lifecycle: ThemeCompatibilityLifecycle,
  private val enabled: AtomicBoolean,
  private val replace: (Method, String, XposedInterface.Hooker) -> Unit,
  private val reportFailure: (String, Throwable) -> Unit,
  maintenance: () -> Unit,
) {
  private data class Request(
    val context: Context,
    val initialType: Int,
    val hasSnapshot: Boolean,
    var attrs: Any? = null,
  )
  private val current = ThreadLocal<Request>()
  private val sequence = AtomicInteger()
  private val artwork = SplashIconArtwork(maintenance)

  fun prepareArtwork(context: Context?) = artwork.prepare(context)
  fun clearArtwork() = artwork.clear()
  fun close() = artwork.close()

  fun install() {
    val drawer = Class.forName(DRAWER, false, loader)
    val builder = Class.forName("$DRAWER\$SplashViewBuilder", false, loader)
    val attrs = Class.forName("$DRAWER\$SplashScreenWindowAttrs", false, loader)
    val info = Class.forName("android.window.StartingWindowInfo", false, loader)
    fun Class<*>.field(name: String) = getDeclaredField(name).apply { isAccessible = true }
    val snapshot = info.field("mQSFileDescriptor")
    val type = builder.field("mSuggestType")
    val overlay = builder.field("mOverlayDrawable")
    val color = builder.field("mThemeColor")
    val activity = builder.field("mActivityInfo")
    val icon = attrs.field("mSplashScreenIcon")
    val brand = attrs.field("mBrandingImage")
    val background = attrs.field("mWindowBgResId")
    val backgroundColor = attrs.field("mWindowBgColor")
    val guard = SystemUiHookGuard { reportFailure("splash icon fallback", it) }
    val make = drawer.getDeclaredMethod("makeSplashScreenContentView", Context::class.java,
      info, Int::class.javaPrimitiveType, java.util.function.Consumer::class.java)
    replace(make, "$PREFIX.request", XposedInterface.Hooker { chain ->
      if (!lifecycle.enter()) chain.proceed() else try {
        val request = guard.run<Request?>(null) {
          val context = chain.getArg(0) as Context
          if (!enabled.get() && context.packageName != BuildConfig.APPLICATION_ID) null else {
            val window = chain.getArg(1)
            Request(context, chain.getArg(2) as Int,
              snapshot.get(window) != null)
          }
        }
        val previous = current.get()
        if (request == null) current.remove() else current.set(request)
        try { chain.proceed() } finally {
          if (previous == null) current.remove() else current.set(previous)
        }
      } finally { lifecycle.leave() }
    })
    val read = drawer.getDeclaredMethod("getWindowAttrs", Context::class.java, attrs)
    replace(read, "$PREFIX.attributes", XposedInterface.Hooker { chain ->
      val original = chain.proceed()
      if (lifecycle.enter()) try {
        guard.run(Unit) { current.get()?.attrs = chain.getArg(1) }
      } finally { lifecycle.leave() }
      original
    })
    val build = builder.getDeclaredMethod("build")
    replace(build, "$PREFIX.build", XposedInterface.Hooker { chain ->
      val request = current.get()
      if (request == null || !lifecycle.enter()) chain.proceed() else try {
        val started = SystemClock.elapsedRealtimeNanos()
        val diagnostics = sequence.incrementAndGet() <= 32
        val tracing = Trace.isEnabled()
        if (tracing) Trace.beginSection("KeiHomeSplash/prepare")
        val owner = chain.thisObject
        var restore: (() -> Unit)? = null
        val detail = guard.run("unavailable") {
          val values = request.attrs ?: return@run "missing-attributes"
          val ai = activity.get(owner) as ActivityInfo
          val oldType = type.getInt(owner)
          // KeiHome owns its declared launch icon. Keep that icon in sync with the
          // applied theme while retaining protection for every other app's artwork.
          val paletteState = if (ai.packageName == BuildConfig.APPLICATION_ID)
            Settings.System.getLong(request.context.contentResolver, NativePaletteIcon.STATE_KEY, 0L) else 0L
          val ownPalette = SplashIconFallbackPolicy.ownPalette(ai.packageName, BuildConfig.APPLICATION_ID, paletteState)
          val earlyReason = SplashIconFallbackPolicy.beforeBackground(
            enabled.get(), request.initialType, oldType, request.hasSnapshot,
            icon.get(values) != null, brand.get(values) != null, ownPalette)
          if (earlyReason != null) return@run if (diagnostics)
            "package=${ai.packageName} reason=$earlyReason" else ""
          val oldOverlay = overlay.get(owner) as? Drawable
          val oldColor = color.getInt(owner)
          val overlayColor = SplashBackgroundDrawable.backgroundColor(oldOverlay)
          val declaredColor = backgroundColor.getInt(values)
          val res = background.getInt(values)
          val windowColor = if (declaredColor != 0) declaredColor
            else if (res == 0) oldColor
            else SplashBackgroundDrawable.backgroundColor(request.context.getDrawable(res))
          val windowFlags = request.context.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating,
            android.R.attr.windowDisablePreview))
          val protected = try { (0..2).any { windowFlags.getBoolean(it, false) } }
            finally { windowFlags.recycle() }
          val input = SplashIconFallbackPolicy.Input(
            enabled.get(), request.initialType, oldType,
            request.hasSnapshot, icon.get(values) != null, brand.get(values) != null,
            protected || ai.applicationInfo.uid / 100000 != android.os.Process.myUid() / 100000,
            windowColor != null && Color.alpha(windowColor) == 255,
            oldOverlay == null || overlayColor != null && Color.alpha(overlayColor) == 255,
            ownPalette)
          val reason = SplashIconFallbackPolicy.reason(input)
          var source = "native"
          var cache = "unused"
          if (reason == "fill") {
            if (ownPalette && !enabled.get()) artwork.prepare(request.context)
            val snapshot = artwork.load(request.context, ai, paletteState.takeIf { ownPalette })
            val themed = snapshot.bitmap
            cache = snapshot.cache
            val oldIcon = icon.get(values)
            // Preserve the effective background and Shell's normal view/animation ownership.
            // Set rollback before the first mutation, including partially failed reflection.
            restore = {
              type.setInt(owner, oldType)
              overlay.set(owner, oldOverlay)
              color.setInt(owner, oldColor)
              icon.set(values, oldIcon)
            }
            try {
              type.setInt(owner, 1)
              overlay.set(owner, null)
              if (ownPalette) color.setInt(owner, os.kei.hyper.home.data.MonetLaunchSurface.surfaceColor(paletteState, windowColor ?: oldColor))
              else if (overlayColor != null) color.setInt(owner, overlayColor)
              if (themed != null) {
                icon.set(values, SplashIconArtwork.drawable(request.context, themed))
                source = "applied-theme"
              }
            } catch (failure: Throwable) {
              restore?.invoke()
              restore = null
              throw failure
            }
          }
          if (diagnostics) "package=${ai.packageName} initial=${request.initialType} final=$oldType " +
            "qs=${request.hasSnapshot} overlay=${oldOverlay?.javaClass?.simpleName} " +
            "icon=${input.hasExplicitIcon} branding=${input.hasBranding} " +
            "windowColor=$windowColor background=$oldColor reason=$reason source=$source cache=$cache ownPalette=$ownPalette " +
            "paletteSurface=${if (ownPalette) os.kei.hyper.home.data.MonetLaunchSurface.surfaceColor(paletteState, windowColor ?: oldColor) else windowColor}"
          else ""
        }
        if (tracing) Trace.endSection()
        val prepared = SystemClock.elapsedRealtimeNanos()
        try {
          val result = chain.proceed()
          guard.run(Unit) {
            // Bound diagnostics in a long-running SystemUI session.
            if (diagnostics) {
              val iconView = result?.javaClass?.getMethod("getIconView")?.invoke(result)
              val finished = SystemClock.elapsedRealtimeNanos()
              xposed.log(Log.INFO, TAG, "$detail iconView=${iconView?.javaClass?.simpleName} " +
                "prepareUs=${(prepared - started) / 1000} buildUs=${(finished - prepared) / 1000}")
            }
          }
          result
        } finally {
          // Rollback must still run if diagnostics disabled the optional-work guard.
          restore?.let { rollback ->
            SystemUiHookGuard { reportFailure("splash icon rollback", it) }.run(Unit, rollback)
          }
        }
      } finally { lifecycle.leave() }
    })
    xposed.log(Log.INFO, TAG, "Missing splash icon fallback ready; enabled=${enabled.get()}")
  }

  companion object {
    private const val DRAWER = "com.android.wm.shell.startingsurface.SplashscreenContentDrawer"
    private const val PREFIX = "keihome.systemui.splash.icons"
    private const val TAG = "KeiHomeSplashIcon"
  }
}
