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
  private val hostContext: () -> Context? = { null },
) {
  private data class Request(
    val context: Context,
    val initialType: Int,
    val hasSnapshot: Boolean,
    val startingFlags: Int?,
    var attrs: Any? = null,
    var declaredIcon: Drawable? = null,
    var completedIcon: Drawable? = null,
    var iconSize: Int = 0,
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
    val startingFlags = runCatching {
      info.field("startingWindowTypeParameter").takeIf { it.type == Int::class.javaPrimitiveType }
    }.getOrNull()
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
              snapshot.get(window) != null, startingFlags?.getInt(window))
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
    // A complete launcher icon already includes its own plate and padding. On known Shell
    // builders, keep the pre-expansion size and give the native ImageView its prepared drawable.
    val finalSize = runCatching {
      val size = builder.field("mFinalIconSize").also { require(it.type == Int::class.javaPrimitiveType) }
      val layers = builder.field("mFinalIconDrawables").also { require(it.type == Array<Drawable>::class.java) }
      val create = builder.getDeclaredMethod("createIconDrawable", Drawable::class.java,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
      require(create.returnType == Void.TYPE)
      xposed.deoptimize(build)
      replace(create, "$PREFIX.completedIcon", XposedInterface.Hooker { chain ->
        val request = current.get()
        // This synchronous call is already covered by the enclosing build's lifecycle lease.
        val drawable = request?.completedIcon
        if (drawable == null || request.declaredIcon !== chain.getArg(0)) return@Hooker chain.proceed()
        val owner = chain.thisObject
        val installed = guard.run(false) {
          layers.set(owner, arrayOf(drawable))
          size.setInt(owner, request.iconSize)
          true
        }
        if (installed) null else chain.proceed()
      })
      size
    }.getOrElse {
      xposed.log(Log.WARN, TAG, "Using Shell's default icon sizing", recoverableSystemUiFailure(it))
      null
    }
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
          val protected: Boolean
          val previewDisabled: Boolean
          try {
            val flags = (0..2).filter { windowFlags.getBoolean(it, false) }
            protected = flags.any { it < 2 }
            previewDisabled = 2 in flags
          } finally { windowFlags.recycle() }
          val input = SplashIconFallbackPolicy.Input(
            enabled.get(), request.initialType, oldType,
            request.hasSnapshot, icon.get(values) != null, brand.get(values) != null,
            protected || ai.applicationInfo.uid / 100000 != android.os.Process.myUid() / 100000,
            windowColor != null && Color.alpha(windowColor) == 255,
            oldOverlay == null || overlayColor != null && Color.alpha(overlayColor) == 255,
            ownPalette,
            previewDisabled = previewDisabled,
            startingFlags = request.startingFlags,
            neutralBacking = previewDisabled &&
              windowColor?.let(SplashBackgroundPolicy::isNeutral) == true &&
              (oldOverlay == null || overlayColor?.let(SplashBackgroundPolicy::isNeutral) == true))
          var reason = SplashIconFallbackPolicy.reason(input)
          var source = "native"
          var cache = "unused"
          if (reason == "fill") {
            if (ownPalette && !enabled.get()) artwork.prepare(hostContext() ?: request.context)
            val result = artwork.load(request.context, ai, paletteState.takeIf { ownPalette })
            cache = result.cache
            val snapshot = result.snapshot
            if (snapshot == null) {
              reason = "no-artwork"
            } else {
              val drawable = SplashIconArtwork.drawable(request.context, snapshot.bitmap)
              val oldIcon = icon.get(values)
              val baseSize = if (ownPalette) 0 else finalSize?.getInt(owner) ?: 0
              // Commit the type and prepared drawable together. Shell's legacy icon path
              // would otherwise wrap and resize the raw APK drawable.
              restore = {
                request.declaredIcon = null
                request.completedIcon = null
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
                icon.set(values, drawable)
                if (baseSize > 0) {
                  request.iconSize = baseSize
                  request.declaredIcon = drawable
                  request.completedIcon = SplashIconArtwork.completedDrawable(request.context, snapshot.bitmap)
                }
                source = snapshot.source
              } catch (failure: Throwable) {
                restore?.invoke()
                restore = null
                throw failure
              }
            }
          }
          if (diagnostics) "package=${ai.packageName} initial=${request.initialType} final=$oldType " +
            "qs=${request.hasSnapshot} overlay=${oldOverlay?.javaClass?.simpleName} " +
            "icon=${input.hasExplicitIcon} branding=${input.hasBranding} " +
            "previewDisabled=$previewDisabled startingFlags=${request.startingFlags?.toUInt()?.toString(16)} " +
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
