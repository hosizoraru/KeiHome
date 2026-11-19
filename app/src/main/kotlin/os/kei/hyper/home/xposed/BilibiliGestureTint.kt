package os.kei.hyper.home.xposed

import android.app.Activity
import android.database.ContentObserver
import android.provider.Settings
import android.app.ActivityManager
import android.app.BroadcastOptions
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import os.kei.hyper.home.data.NavigationBackdropPreferences
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.BILIBILI_PACKAGE

private const val ACTION = "os.kei.hyper.home.BILIBILI_FOOTER_TINT"

internal class BilibiliGestureTint(private val activity: Activity) {
  private val token = Binder()
  private var last: Int? = null

  fun publish(color: Int?) {
    if (last == color || activity.display?.displayId != Display.DEFAULT_DISPLAY) return
    val intent = Intent(ACTION).setPackage(SystemUiThemeCompatibilityRuntime.PACKAGE_NAME)
      .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND)
      .putExtras(Bundle().apply { putBinder("session", token) })
      .putExtra("build", BuildConfig.VERSION_CODE).putExtra("activity", activity.javaClass.name)
    if (color != null) intent.putExtra("color", color)
    activity.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    last = color
  }
}

/** Apply color inside the OEM handle draw, retaining its shape, opacity, hit target and animations. */
internal class SystemUiGestureTint(private val context: Context, private val prefs: SharedPreferences) {
  private val main = Handler(Looper.getMainLooper())
  private val keyguard = context.getSystemService(KeyguardManager::class.java)
  private val tasks = context.getSystemService(ActivityManager::class.java)
  private val handleId = context.resources.getIdentifier("home_handle", "id", context.packageName)
  @Volatile private var color: Int? = null
  @Volatile private var closed = false
  private var allowedOwner: Boolean? = null
  private var owner: IBinder? = null
  private var ownerUid = -1
  private var death: IBinder.DeathRecipient? = null
  private var registered = false
  private val windows = context.getSystemService(WindowManager::class.java)
  private var watchingBlur = false
  private val blurListener = java.util.function.Consumer<Boolean> { if (!closed) invalidateHandle() }
  private var glass: GestureHandleGlass? = null
  private lateinit var handleClass: Class<*>
  @Volatile private var glassOn = false
  private val style = object : ContentObserver(main) {
    override fun onChange(selfChange: Boolean) { if (!closed) refreshStyle() }
  }
  private fun refreshStyle() {
    glass?.allowVendor = Settings.Secure.getInt(context.contentResolver, "material_style", -1) == 1
    invalidateHandle()
  }
  private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
    main.post { refreshGlass() }
  }
  private fun refreshGlass() {
    if (closed) return
    val choice = runCatching { NavigationBackdropPreferences.read(prefs) }.getOrNull()
    val wasOn = glassOn
    glassOn = choice?.glassHandle == true
    if (owner != null && (glassOn || choice?.includesBilibiliArtwork(ownerUid) != true)) reset()
    if (glassOn && !watchingBlur && windows != null) {
      if (glass == null) glass = runCatching { GestureHandleGlass(handleClass) }.getOrNull()
      windows.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
      context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("material_style"), false, style)
      refreshStyle()
      watchingBlur = true
    } else if (!glassOn && watchingBlur) {
      windows?.removeCrossWindowBlurEnabledListener(blurListener)
      context.contentResolver.unregisterContentObserver(style)
      watchingBlur = false
    }
    if (wasOn && !glassOn) {
      glass?.clear()
      invalidateHandle()
    }
  }

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (closed || sentFromPackage != BILIBILI_PACKAGE) return
      if (intent.getIntExtra("build", 0) != BuildConfig.VERSION_CODE) return
      val session = intent.extras?.getBinder("session") ?: return
      if (!intent.hasExtra("color")) {
        if (owner == session) reset()
        return
      }
      @Suppress("DEPRECATION")
      val top = tasks?.getRunningTasks(1)?.firstOrNull()?.topActivity
      val choice = runCatching { NavigationBackdropPreferences.read(prefs) }.getOrNull() ?: return
      if (choice.glassHandle || !choice.includesBilibiliArtwork(sentFromUid) || allowedOwner == false ||
        top?.packageName != BILIBILI_PACKAGE || top.className != intent.getStringExtra("activity") ||
        keyguard?.isKeyguardLocked != false) return
      val tint = intent.getIntExtra("color", 0)
      if (tint ushr 24 == 0) return
      if (owner != session) {
        reset()
        val listener = IBinder.DeathRecipient { main.post { if (owner == session) reset() } }
        try { session.linkToDeath(listener, 0) } catch (_: RemoteException) { return }
        owner = session
        ownerUid = sentFromUid
        death = listener
      }
      if (color == tint) return
      color = tint
      invalidateHandle()
    }
  }

  fun install(loader: ClassLoader, replace: (Method, String, XposedInterface.Hooker) -> Unit) {
    require(handleId != 0)
    val handle = loader.loadClass("com.android.systemui.navigationbar.gestural.NavigationHandle")
    handleClass = handle
    val paint = handle.getDeclaredField("mPaint").apply { isAccessible = true }
    require(paint.type == Paint::class.java)
    val bar = loader.loadClass("com.android.systemui.navigationbar.views.NavigationBar")
    val attrs = bar.declaredMethods.single { it.name == "onSystemBarAttributesChanged" && it.parameterCount == 8 &&
      it.parameterTypes[0] == Int::class.javaPrimitiveType && it.parameterTypes[3] == Boolean::class.javaPrimitiveType &&
      it.parameterTypes[6] == String::class.java }
    replace(attrs, "keihome.systemui.gesture.owner", XposedInterface.Hooker { chain ->
      if (!closed && chain.getArg(0) == Display.DEFAULT_DISPLAY) {
        allowedOwner = chain.getArg(3) == false && chain.getArg(6) == BILIBILI_PACKAGE
        if (allowedOwner == false && owner != null) reset()
      }
      chain.proceed()
    })
    replace(handle.getDeclaredMethod("onDraw", Canvas::class.java), "keihome.systemui.gesture.tint", XposedInterface.Hooker { chain ->
      val tint = color
      val view = chain.thisObject as View
      if (closed || (!glassOn && tint == null) || view.id != handleId || view.display?.displayId != Display.DEFAULT_DISPLAY) chain.proceed()
      else {
        val brush = paint.get(view) as Paint
        val old = brush.color
        if (glassOn && runCatching { glass?.draw(view, chain.getArg(0) as Canvas, old) }.getOrDefault(false) == true) {
          return@Hooker null
        }
        if (tint == null) return@Hooker chain.proceed()
        // The sampled paint includes its contrast alpha. SystemUI still owns the view animation.
        brush.color = tint
        try { chain.proceed() } finally { brush.color = old }
      }
    })
    context.registerReceiver(receiver, IntentFilter(ACTION), null, main, Context.RECEIVER_EXPORTED)
    registered = true
    prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    main.post { refreshGlass() }
  }

  private fun invalidateHandle() {
    for (root in WindowInspector.getGlobalWindowViews()) {
      if (root.display?.displayId == Display.DEFAULT_DISPLAY && root.findViewById<View>(handleId) != null) {
        invalidateHandles(root)
      }
    }
  }

  private fun invalidateHandles(view: View) {
    if (!view.isShown) return
    if (view.id == handleId) {
      view.invalidate()
    } else if (view is ViewGroup) {
      for (index in 0 until view.childCount) invalidateHandles(view.getChildAt(index))
    }
  }

  private fun reset() {
    death?.let { owner?.unlinkToDeath(it, 0) }
    owner = null
    ownerUid = -1
    death = null
    if (color != null) {
      color = null
      invalidateHandle()
    }
  }

  /** Drain main-thread view ownership before API 102 admits the successor. */
  fun closeBefore(deadline: Long): Boolean {
    if (Looper.myLooper() == main.looper) {
      dispose()
      return true
    }
    val release = java.util.concurrent.FutureTask { dispose() }
    if (!main.postAtFrontOfQueue(release)) return false
    return try {
      release.get((deadline - System.nanoTime()).coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS)
      true
    } catch (_: java.util.concurrent.TimeoutException) {
      release.cancel(false)
      main.removeCallbacks(release)
      false
    }
  }

  fun close() {
    if (Looper.myLooper() == main.looper) dispose() else main.post { dispose() }
  }

  private fun dispose() {
    if (closed) return
    closed = true
    if (registered) {
      context.unregisterReceiver(receiver)
      prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }
    if (watchingBlur) {
      windows?.removeCrossWindowBlurEnabledListener(blurListener)
      context.contentResolver.unregisterContentObserver(style)
    }
    glass?.clear()
    reset()
    invalidateHandle()
  }
}
