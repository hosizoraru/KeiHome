package os.kei.hyper.home.xposed

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Process
import android.os.UserHandle
import android.util.SparseArray
import java.util.concurrent.atomic.AtomicBoolean
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.data.WallpaperDimStrengths
import os.kei.hyper.home.data.WallpaperDimmingPreferences

/** Owned by the system-server control worker. The wallpaper service owns all rendering and requests. */
internal class WallpaperDimmingControl(
  private val context: Context,
  private val handler: Handler,
  private val registrations: MonitorRegistrations,
  private val openPreferences: () -> SharedPreferences,
  private val surfaces: WallpaperSurfaceDimming?,
  private val active: () -> Boolean,
  private val onFailure: (Throwable) -> Unit,
  private val onPreferencesReady: (SharedPreferences) -> Unit,
) {
  private var preferences: SharedPreferences? = null
  private var platform: WallpaperDimRequests? = null
  private var retry = 0
  private var removedUid: Int? = null
  private var publishedStrengths: WallpaperDimStrengths? = null
  private val refreshSurfaces = AtomicBoolean(true)
  private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (WallpaperDimmingPreferences.affectsDimming(key)) request()
  }

  private val synchronize = object : Runnable {
    override fun run() {
      if (!active()) return
      try {
        val prefs = if (removedUid == null) connectPreferences() else null
        val uid = removedUid ?: context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
        val service = platform ?: WallpaperDimRequests.resolve(context).also { platform = it }
        val config = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val strengths = if (prefs != null && config == Configuration.UI_MODE_NIGHT_YES &&
          WallpaperDimmingPreferences.enabled(prefs)) WallpaperDimmingPreferences.strengths(prefs)
          else WallpaperDimStrengths.Off
        if (!active()) return
        if (surfaces != null) {
          // Remove this module's old global request once, preserving every other UID.
          service.update(uid, 0f)
          surfaces.update(context, service.service, strengths, refreshSurfaces.getAndSet(false))
        } else {
          service.update(uid, strengths.forScreen(lockScreen = false) / 100f)
        }
        if (publishedStrengths != strengths) {
          context.sendBroadcast(Intent(WALLPAPER_DIM_CHANGED).setPackage("com.android.systemui"),
            WALLPAPER_DIM_PERMISSION)
          publishedStrengths = strengths
        }
        retry = 0
      } catch (_: PackageManager.NameNotFoundException) {
        // KeiHome is unavailable for this user.
      } catch (failure: ReflectiveOperationException) {
        onFailure(failure)
      } catch (failure: Exception) {
        if (retry++ < 3) handler.postDelayed(this, 1_000)
        else onFailure(failure)
      }
    }
  }

  fun start() {
    val events = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) = request(force = true)
    }
    val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED).apply {
      addAction(Intent.ACTION_WALLPAPER_CHANGED)
      addAction(Intent.ACTION_USER_UNLOCKED)
      addAction("android.intent.action.USER_SWITCHED")
    }
    registrations.own { context.unregisterReceiver(events) }
    context.registerReceiver(events, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)

    val packageChanges = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
        if (uid < 0 || UserHandle.getUserHandleForUid(uid) != Process.myUserHandle() ||
          intent.data?.schemeSpecificPart != BuildConfig.APPLICATION_ID) return
        removedUid = uid.takeIf { intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED }
        request()
      }
    }
    registrations.own { context.unregisterReceiver(packageChanges) }
    context.registerReceiver(packageChanges, IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
      addAction(Intent.ACTION_PACKAGE_ADDED)
      addDataScheme("package")
    }, null, handler, Context.RECEIVER_NOT_EXPORTED)
    request()
  }

  fun request(force: Boolean = false) {
    if (!active()) return
    if (force) refreshSurfaces.set(true)
    handler.removeCallbacks(synchronize)
    handler.post(synchronize)
  }

  private fun connectPreferences(): SharedPreferences = preferences ?: openPreferences().also {
    registrations.own { it.unregisterOnSharedPreferenceChangeListener(listener) }
    it.registerOnSharedPreferenceChangeListener(listener)
    preferences = it
    onPreferencesReady(it)
  }
}

private class WallpaperDimRequests(val service: Any) {
  private val type = service.javaClass
  private val lock = requireNotNull(type.getDeclaredField("mLock").apply { isAccessible = true }.get(service))
  private val user = type.getDeclaredField("mCurrentUserId").apply { isAccessible = true }
  private val wallpapers = type.getDeclaredField("mWallpaperMap").apply { isAccessible = true }
  private val requests = Class.forName("com.android.server.wallpaper.WallpaperData", false, type.classLoader)
    .getDeclaredField("mUidToDimAmount").apply { isAccessible = true }
  private val setAmount = type.getMethod("setWallpaperDimAmountForUid", Int::class.javaPrimitiveType,
    Float::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

  // The service selects the foreground user internally. Hold its lock across the user
  // check and call, so a concurrent switch cannot apply this UID to another user's data.
  fun update(uid: Int, amount: Float) = synchronized(lock) {
    if (user.getInt(service) != 0) return@synchronized
    val wallpaper = checkNotNull((wallpapers.get(service) as SparseArray<*>)[0]) { "Wallpaper data unavailable" }
    val existing = (requests.get(wallpaper) as SparseArray<*>)[uid] as? Float ?: 0f
    if (existing != amount) setAmount.invoke(service, uid, amount, -1, false)
  }

  companion object {
    fun resolve(context: Context): WallpaperDimRequests {
      val manager = context.getSystemService(WallpaperManager::class.java)
      val service = WallpaperManager::class.java.getMethod("getIWallpaperManager").invoke(manager)
      return WallpaperDimRequests(checkNotNull(service) { "Wallpaper service unavailable" })
    }
  }
}
