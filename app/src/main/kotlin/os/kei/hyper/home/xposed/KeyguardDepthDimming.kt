package os.kei.hyper.home.xposed

import android.animation.ValueAnimator
import android.app.WallpaperManager
import android.content.ComponentCallbacks
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import java.util.WeakHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt
import os.kei.hyper.home.data.WallpaperDimmingPreferences

internal const val WALLPAPER_DIM_CHANGED = "os.kei.hyper.home.action.WALLPAPER_DIM_CHANGED"
internal const val WALLPAPER_DIM_PERMISSION = "android.permission.SET_WALLPAPER_DIM_AMOUNT"

/** SystemUI places the extracted subject above the wallpaper surface, interleaved with the clock. */
internal class KeyguardDepthDimming(
  private val context: Context,
  private val preferences: SharedPreferences,
  private val guard: SystemUiHookGuard,
) {
  private val main = Handler(Looper.getMainLooper())
  private val subjectId = context.resources.getIdentifier("deducted_image_view", "id", context.packageName)
  private val windowsClass = Class.forName("android.view.WindowManagerGlobal")
  private val windows = windowsClass.getMethod("getInstance").invoke(null)
  private val getWindowViews = windowsClass.getMethod("getWindowViews")
  private val wallpaper = context.getSystemService(WallpaperManager::class.java)
  private val getDimAmount = WallpaperManager::class.java.getMethod("getWallpaperDimAmount")
  private val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
  private val getConfiguration = Class.forName("android.app.IActivityManager").getMethod("getConfiguration")
  private val views = WeakHashMap<ImageView, LightingColorFilter?>()
  private val registrations = MonitorRegistrations()
  private var closed = false
  private var amount = 0f
  private var targetAmount = 0f
  private var animation: ValueAnimator? = null
  private var level = 255
  private val refresh = Runnable { guard.run(Unit) { refreshAmount() } }
  private val initialize = Runnable {
    guard.run(Unit) {
      @Suppress("UNCHECKED_CAST")
      for (root in getWindowViews.invoke(windows) as List<View>) capture(root)
      refreshAmount()
    }
  }
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (WallpaperDimmingPreferences.affectsDimming(key)) requestRefresh()
  }
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = requestRefresh()
  }
  private val configurationChanges = object : ComponentCallbacks {
    override fun onConfigurationChanged(newConfig: Configuration) = requestRefresh()
    override fun onLowMemory() = Unit
  }

  fun start() {
    check(subjectId != 0) { "Keyguard subject view unavailable" }
    context.registerReceiver(receiver, IntentFilter(WALLPAPER_DIM_CHANGED), WALLPAPER_DIM_PERMISSION,
      main, Context.RECEIVER_NOT_EXPORTED)
    registrations.own { context.unregisterReceiver(receiver) }
    context.registerComponentCallbacks(configurationChanges)
    registrations.own { context.unregisterComponentCallbacks(configurationChanges) }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    registrations.own { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    main.post(initialize)
  }

  fun bind(root: View) {
    if (Looper.myLooper() != main.looper) {
      main.post { bind(root) }
      return
    }
    if (closed) return
    capture(root)
    requestRefresh()
  }

  fun bindSubject(view: View) {
    if (view.id == subjectId && view is ImageView) bind(view)
  }

  private fun capture(root: View) {
    val view = root.findViewById<View>(subjectId) as? ImageView ?: return
    if (view.colorFilter == null) {
      views[view] = null
      render(view, level)
    }
  }

  private fun requestRefresh() {
    main.removeCallbacks(refresh)
    main.post(refresh)
  }

  private fun refreshAmount() {
    if (closed) return
    // Match the lock engine's maximum of the user strength and the platform aggregate.
    val next = if (WallpaperDimmingPreferences.enabled(preferences) && isNightMode()) {
      val baseline = (getDimAmount.invoke(wallpaper) as Float).coerceIn(0f, 1f)
      WallpaperDimmingPreferences.strengths(preferences).effectiveAmount(lockScreen = true, baseline)
    } else 0f
    if (next == targetAmount) return
    targetAmount = next
    animation?.cancel()
    // Match WallpaperService.Engine's dim animation. A hidden subject can snap.
    if (views.keys.none { it.isShown && it.isAttachedToWindow } || !ValueAnimator.areAnimatorsEnabled()) {
      applyAmount(next)
      return
    }
    animation = ValueAnimator.ofFloat(amount, next).apply {
      duration = 300
      addUpdateListener { applyAmount(it.animatedValue as Float) }
      start()
    }
  }

  private fun isNightMode(): Boolean {
    // The lock-screen host's resources can still carry the previous mode during a transition.
    val global = getConfiguration.invoke(activityManager) as Configuration
    return global.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
  }

  private fun applyAmount(next: Float) {
    amount = next
    val newLevel = ((1f - next) * 255).roundToInt()
    if (newLevel == level) return
    level = newLevel
    for (view in views.keys.toList()) render(view, level)
  }

  private fun render(view: ImageView, brightness: Int) {
    if (view.colorFilter !== views[view]) return
    val filter = if (brightness == 255) null
      else LightingColorFilter(Color.rgb(brightness, brightness, brightness), 0)
    view.colorFilter = filter
    views[view] = filter
  }

  fun closeBefore(deadline: Long): Boolean {
    if (Looper.myLooper() == main.looper) return close()
    val release = FutureTask(::close)
    if (!main.postAtFrontOfQueue(release)) return false
    return try {
      release.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    } catch (_: TimeoutException) {
      release.cancel(false)
      main.removeCallbacks(release)
      false
    }
  }

  fun close(): Boolean {
    closed = true
    main.removeCallbacksAndMessages(null)
    animation?.cancel()
    animation = null
    applyAmount(0f)
    views.clear()
    return registrations.release { failure -> throw failure }
  }
}
