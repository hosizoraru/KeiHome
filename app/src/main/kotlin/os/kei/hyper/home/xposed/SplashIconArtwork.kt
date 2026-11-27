package os.kei.hyper.home.xposed

import android.app.UiModeManager
import android.content.Context
import android.content.ComponentCallbacks
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.os.Trace
import android.os.Looper
import android.os.Handler
import android.provider.Settings
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import os.kei.hyper.home.iconpack.IconPackComponent
import os.kei.hyper.home.iconpack.NativePaletteIcon
import os.kei.hyper.home.iconpack.OriginalAppIcons
import os.kei.hyper.home.iconpack.OriginalIconShape
import os.kei.hyper.home.iconpack.nativeLauncherActivityName

/** Prepares starting-window artwork from the active icon archive or the stock HyperOS producer. */
internal class SplashIconArtwork(private val maintenance: () -> Unit) {
  data class Snapshot(val bitmap: Bitmap, val source: String)
  data class Result(val snapshot: Snapshot?, val cache: String)
  private data class Source(val fileKey: Any?, val size: Long, val modified: FileTime, val regular: Boolean)
  private data class Key(
    val component: IconPackComponent, val uid: Int, val apk: String?,
    val icon: Int, val theme: Int, val dark: Boolean, val locale: Locale, val palette: Long, val timeZone: String,
    val density: Int, val configurationRevision: Long,
  )
  private val file = File("/data/system/theme/icons")
  private val lock = Any()
  private val snapshots = SplashSnapshotCache<Key, Snapshot>()
  private val weatherTemperature = WeatherIconTemperature()
  private var source: Source? = null
  private var archive: ZipFile? = null
  private var retryAt = 0L
  private var warned = false
  private var closed = false
  private val configurationRevision = AtomicLong()
  private var cachedNightMode: Pair<Long, Int?>? = null
  private var callbackContext: Context? = null
  private val trimRequested = AtomicBoolean()
  private val callbacks = object : ComponentCallbacks {
    override fun onConfigurationChanged(newConfig: Configuration) {
      configurationRevision.incrementAndGet()
      maintenance()
    }
    override fun onLowMemory() {
      trimRequested.set(true)
      maintenance()
    }
  }
  private val nightModeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean) {
      configurationRevision.incrementAndGet()
      maintenance()
    }
  }

  private fun source(): Source? {
    val attrs = try {
      Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    } catch (_: java.nio.file.NoSuchFileException) { return null }
    return Source(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime(), attrs.isRegularFile)
  }

  private fun archive(now: Long): ZipFile? {
    val stamp = source()
    if (stamp != source) {
      clearLocked()
      source = stamp
    }
    if (stamp == null || !stamp.regular || stamp.size !in 1..(1024L * 1024 * 1024) || now < retryAt) return null
    return archive ?: traced("KeiHomeSplash/archive") {
      ZipFile(file).also { archive = it }
    }
  }

  /** Called by existing owned initialization work, never by the SystemUI main thread. */
  fun prepare(context: Context?) = synchronized(lock) {
    if (!closed) safely {
      if (trimRequested.getAndSet(false)) {
        clearLocked()
        return@safely
      }
      if (callbackContext == null && context != null) {
        val application = context.applicationContext ?: context
        application.registerComponentCallbacks(callbacks)
        try {
          // HyperOS can keep SystemUI's own configuration pinned across a global toggle.
          application.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("ui_night_mode"), false, nightModeObserver)
          application.contentResolver.registerContentObserver(
            Settings.System.getUriFor(NativePaletteIcon.STATE_KEY), false, nightModeObserver)
        } catch (failure: Exception) {
          try { application.contentResolver.unregisterContentObserver(nightModeObserver) } catch (_: Exception) { }
          application.unregisterComponentCallbacks(callbacks)
          throw failure
        }
        callbackContext = application
      }
      archive(SystemClock.uptimeMillis())
      if (context != null) nightMode(context)
      Unit
    }
  }

  private fun nightMode(context: Context): Int? {
    val revision = configurationRevision.get()
    cachedNightMode?.takeIf { it.first == revision }?.let { return it.second }
    return traced("KeiHomeSplash/night-mode") {
      (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.nightMode
    }.also { cachedNightMode = revision to it }
  }

  private fun unregister() {
    callbackContext?.let {
      it.contentResolver.unregisterContentObserver(nightModeObserver)
      it.unregisterComponentCallbacks(callbacks)
    }
    callbackContext = null
  }

  fun clear() = synchronized(lock) {
    unregister()
    clearLocked()
  }

  fun close() = synchronized(lock) {
    closed = true
    unregister()
    clearLocked()
  }

  private fun clearLocked() {
    snapshots.clear()
    try { archive?.close() } catch (failure: java.io.IOException) {
      Log.w("KeiHomeSplashIcon", "Closing the retired icon archive failed", failure)
    } finally {
      archive = null
      source = null
      retryAt = 0L
      warned = false
      cachedNightMode = null
    }
  }

  fun load(context: Context, activity: ActivityInfo, paletteOverride: Long? = null): Result = traced("KeiHomeSplash/lookup") {
    if (Looper.myLooper() == Looper.getMainLooper()) return@traced Result(null, "main-thread")
    synchronized(lock) {
      if (closed) return@synchronized Result(null, "closed")
      safely {
        val now = SystemClock.uptimeMillis()
        val zip = archive(now)
        if (zip == null && source != null) return@safely Result(null, "unavailable")
        val config = context.resources.configuration
        val mode = nightMode(context)
        val dark = when (mode) {
          UiModeManager.MODE_NIGHT_YES -> true
          UiModeManager.MODE_NIGHT_NO -> false
          else -> {
            val global = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            (if (global == Configuration.UI_MODE_NIGHT_UNDEFINED)
              config.uiMode and Configuration.UI_MODE_NIGHT_MASK else global) == Configuration.UI_MODE_NIGHT_YES
          }
        }
        val component = IconPackComponent(activity.packageName, activity.name)
        val palette = paletteOverride ?:
          Settings.System.getLong(context.contentResolver, NativePaletteIcon.STATE_KEY, 0L)
        val locale = config.locales[0] ?: Locale.getDefault()
        val key = Key(component, activity.applicationInfo.uid, activity.applicationInfo.sourceDir,
          activity.iconResource, activity.themeResource, dark, locale, palette, TimeZone.getDefault().id,
          config.densityDpi, configurationRevision.get())
        val wallTime = System.currentTimeMillis()
        val minute = wallTime / 60_000
        snapshots.get(key, now, minute)?.let { return@safely Result(it.value, "hit") }
        var dynamic = false
        val snapshot = traced("KeiHomeSplash/render") {
          if (zip == null) return@traced stockSnapshot(context, activity, dark)

          val resolvedMask = SurfaceIconSnapshotRenderer.resolveMask(zip)
          val original by lazy(LazyThreadSafetyMode.NONE) {
            OriginalAppIcons.renderLaunchIcon(context, activity, SurfaceIconSnapshotRenderer.SIZE, dark,
              mask = OriginalIconShape.mask)?.let { Snapshot(it, "original-apk") }
          }
          var preserved = false
          val themed = SurfaceIconSnapshotRenderer.render(zip,
            nativeLauncherActivityName(component) ?: activity.packageName,
            dark, locale, wallTime, palette, packageName = activity.packageName,
            resolvedMask = resolvedMask,
            weatherTemperature = { global -> weatherTemperature.read(context, global, wallTime) },
            onDynamic = { dynamic = true }, originalIcon = {
              preserved = true
              original?.bitmap
            })
          when {
            preserved -> original
            themed != null -> Snapshot(themed, "applied-theme")
            else -> OriginalAppIcons.renderLaunchIcon(context, activity, SurfaceIconSnapshotRenderer.SIZE, dark,
              mask = resolvedMask ?: OriginalIconShape.mask)?.let { Snapshot(it, "original-apk") }
          }
        }
        // The archive or resource configuration can change during decoding.
        val themeChanged = source() != source
        if (themeChanged || key.configurationRevision != configurationRevision.get()) {
          snapshot?.bitmap?.recycle()
          if (themeChanged) clearLocked()
          Result(null, if (themeChanged) "theme-changed" else "configuration-changed")
        } else {
          snapshots.put(key, snapshot, snapshot?.bitmap?.allocationByteCount ?: 0,
            if (snapshot == null) now + 30_000 else Long.MAX_VALUE,
            if (dynamic) minute else null)
          Result(snapshot, "render")
        }
      } ?: Result(null, "unavailable")
    }
  }

  private fun stockSnapshot(context: Context, activity: ActivityInfo, dark: Boolean): Snapshot? {
    val size = SurfaceIconSnapshotRenderer.SIZE
    val native = SplashStockIcons.load(callbackContext ?: context, activity)
    if (native is Animatable) return null
    if (native != null) {
      return Snapshot(OriginalAppIcons.rasterizeLaunchIcon(native, size, OriginalIconShape.mask), "stock-hyperos")
    }
    return OriginalAppIcons.renderLaunchIcon(context, activity, size, dark, OriginalIconShape.mask)
      ?.let { Snapshot(it, "stock-apk") }
  }

  private inline fun <T> safely(block: () -> T): T? = try {
    block()
  } catch (failure: Exception) {
    // A malformed archive must not be parsed, or logged, on every application launch.
    retryAt = SystemClock.uptimeMillis() + 1_000
    if (!warned) {
      warned = true
      Log.w("KeiHomeSplashIcon", "Icon preparation failed; starting window retained", failure)
    }
    null
  }

  companion object {
    fun completedDrawable(context: Context, bitmap: Bitmap) =
      BitmapDrawable(context.resources, bitmap).apply {
        gravity = android.view.Gravity.FILL
        isFilterBitmap = true
      }

    fun drawable(context: Context, bitmap: Bitmap): Drawable {
      val extra = AdaptiveIconDrawable.getExtraInsetFraction()
      // The archive contains a complete icon, not an adaptive foreground layer.
      // Cancel AdaptiveIconDrawable's expanded layer bounds to preserve its full artwork.
      val inset = extra / (1f + 2f * extra)
      return AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT),
        InsetDrawable(BitmapDrawable(context.resources, bitmap), inset))
    }

    private inline fun <T> traced(name: String, block: () -> T): T {
      val enabled = Trace.isEnabled()
      if (enabled) Trace.beginSection(name)
      return try { block() } finally { if (enabled) Trace.endSection() }
    }
  }
}
