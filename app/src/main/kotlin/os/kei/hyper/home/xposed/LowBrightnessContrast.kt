package os.kei.hyper.home.xposed

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PatternMatcher
import android.view.Display
import android.view.SurfaceControl
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.WallpaperDimmingPreferences

/** A black color layer reduces the range of output luminance at low panel brightness. */
internal class LowBrightnessContrast(
  private val context: Context,
  private val handler: Handler,
  private val registrations: MonitorRegistrations,
  private val active: () -> Boolean,
  private val onFailure: (Throwable) -> Unit,
) {
  private val displayManager = context.getSystemService(DisplayManager::class.java)
  private val currentUser by lazy { ActivityManager::class.java.getMethod("getCurrentUser") }
  private val setColor by lazy {
    SurfaceControl.Transaction::class.java.getMethod("setColor", SurfaceControl::class.java, FloatArray::class.java)
  }
  private var surface: SurfaceControl? = null
  private var appliedAlpha = 0f
  private var displayRegistered = false
  private var enabled = false
  private var packageAvailable = true
  private var failed = false
  private val refresh = Runnable { update() }
  private val displayListener = object : DisplayManager.DisplayListener {
    override fun onDisplayAdded(id: Int) = onDisplayChanged(id)
    override fun onDisplayRemoved(id: Int) = onDisplayChanged(id)
    override fun onDisplayChanged(id: Int) {
      if (id == Display.DEFAULT_DISPLAY) request()
    }
  }

  fun start(preferences: SharedPreferences) {
    val manager = displayManager ?: return
    registrations.own {
      if (displayRegistered) {
        manager.unregisterDisplayListener(displayListener)
        displayRegistered = false
      }
    }
    registrations.own(::removeMask)
    val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == null || key == WallpaperDimmingPreferences.LOW_CONTRAST) {
        handler.post { readPreference(preferences) }
      }
    }
    preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    registrations.own { preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) = request()
    }
    context.registerReceiver(receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED).apply {
      addAction("android.intent.action.USER_SWITCHED")
      addAction(Intent.ACTION_USER_UNLOCKED)
    }, null, handler, Context.RECEIVER_NOT_EXPORTED)
    registrations.own { context.unregisterReceiver(receiver) }
    val packageReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        packageAvailable = intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED
        request()
      }
    }
    context.registerReceiver(packageReceiver, IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addDataScheme("package")
      addDataSchemeSpecificPart(BuildConfig.APPLICATION_ID, PatternMatcher.PATTERN_LITERAL)
    }, null, handler, Context.RECEIVER_NOT_EXPORTED)
    registrations.own { context.unregisterReceiver(packageReceiver) }
    readPreference(preferences)
  }

  private fun readPreference(preferences: SharedPreferences) {
    if (!active()) return
    runCatching {
      enabled = preferences.getBoolean(WallpaperDimmingPreferences.LOW_CONTRAST, false)
      failed = false
      val manager = checkNotNull(displayManager)
      if (enabled && !displayRegistered) {
        manager.registerDisplayListener({ task -> handler.post(task) },
          DisplayManager.EVENT_TYPE_DISPLAY_BRIGHTNESS or DisplayManager.EVENT_TYPE_DISPLAY_STATE or
            DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_REMOVED,
          displayListener)
        displayRegistered = true
      } else if (!enabled && displayRegistered) {
        manager.unregisterDisplayListener(displayListener)
        displayRegistered = false
      }
      request()
    }.onFailure(::reportFailure)
  }

  private fun request() {
    if (!active()) return
    handler.removeCallbacks(refresh)
    handler.post(refresh)
  }

  private fun update() {
    if (!active() || failed) return
    runCatching {
      val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
      val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
      val alpha = if (enabled && packageAvailable && night == Configuration.UI_MODE_NIGHT_YES &&
        display?.state == Display.STATE_ON && currentUser.invoke(null) == 0) {
        lowBrightnessMaskAlpha(readPanelBrightnessNits(checkNotNull(displayManager)))
      } else 0f
      if (alpha == 0f) removeMask()
      else if (alpha != appliedAlpha || surface?.isValid != true) applyMask(alpha)
    }.onFailure(::reportFailure)
  }

  // The framework accepts the internal nits unit, which the public SDK omits from its IntDef.
  @SuppressLint("WrongConstant")
  private fun readPanelBrightnessNits(manager: DisplayManager): Float =
    manager.getBrightness(Display.DEFAULT_DISPLAY, BRIGHTNESS_UNIT_NITS)

  private fun reportFailure(failure: Throwable) {
    failed = true
    runCatching(::removeMask).exceptionOrNull()?.let(failure::addSuppressed)
    onFailure(failure)
  }

  private fun applyMask(alpha: Float) {
    val mask = surface?.takeIf { it.isValid } ?: makeMask().also {
      surface?.release()
      surface = it
    }
    SurfaceControl.Transaction().use {
      setColor.invoke(it, mask, BLACK)
      it.setLayer(mask, Int.MAX_VALUE - 4).setAlpha(mask, alpha).setVisibility(mask, true).apply()
    }
    appliedAlpha = alpha
  }

  private fun makeMask(): SurfaceControl {
    val serviceManager = Class.forName("android.os.ServiceManager")
    val wm = checkNotNull(serviceManager.getMethod("getService", String::class.java).invoke(null, "window"))
    val lock = checkNotNull(wm.javaClass.getDeclaredField("mGlobalLock").apply { isAccessible = true }.get(wm))
    return synchronized(lock) {
      val display = checkNotNull(wm.javaClass.getDeclaredMethod("getDefaultDisplayContentLocked")
        .apply { isAccessible = true }.invoke(wm))
      val builder = display.javaClass.getDeclaredMethod("makeOverlay").apply { isAccessible = true }
        .invoke(display) as SurfaceControl.Builder
      SurfaceControl.Builder::class.java.getMethod("setColorLayer").invoke(builder)
      builder.setName("KeiHome low brightness contrast").build()
    }
  }

  private fun removeMask() {
    val mask = surface ?: return
    if (mask.isValid) SurfaceControl.Transaction().use {
      it.setVisibility(mask, false).reparent(mask, null).apply()
    }
    mask.release()
    surface = null
    appliedAlpha = 0f
  }

  private companion object {
    const val BRIGHTNESS_UNIT_NITS = 2
    val BLACK = floatArrayOf(0f, 0f, 0f)
  }
}

// Ramp to the ColorOS maximum at the panel minimum. Taper the effect to zero at the cutoff.
internal fun lowBrightnessMaskAlpha(nits: Float): Float {
  if (!nits.isFinite() || nits <= 0f || nits >= 20f) return 0f
  return (0.30f * (1f - nits / 20f)).coerceIn(0f, 0.30f)
}
