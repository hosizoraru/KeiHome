package os.kei.hyper.home.ui.main

import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import os.kei.hyper.home.data.AppIconPreviews
import os.kei.hyper.home.iconpack.IconPackEntry
import os.kei.hyper.home.status.SystemIconThemeReport

internal data class AppleCurrentThemePreview(val light: Bitmap, val dark: Bitmap? = null)

internal fun appleCurrentThemeSourceReady(theme: SystemIconThemeReport?, source: IconBrowserSource?): Boolean =
  theme != null && theme.complete && theme.source != SystemIconThemeReport.Source.UNAVAILABLE &&
    theme.previewKey != null && theme.previewKey == source?.revision

internal fun appleCurrentThemeEntry(source: IconBrowserSource, packageName: String,
  componentName: String?): IconPackEntry? =
  componentName?.let { source.entries[it] } ?: source.entries[packageName]

/** Uses the applied archive only. An absent/unreadable entry is not proof of an APK fallback. */
internal class AppleCurrentThemePreviewProvider(
  private val theme: SystemIconThemeReport?,
  private val source: IconBrowserSource?,
  private val colors: IconPreviewColors?,
  private val monochromeEnabled: Boolean,
  private val componentFor: suspend (String) -> String?,
  private val loadCurrent: suspend (IconBrowserSource, IconPackEntry) -> AppIconPreviews,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val worker = dispatcher.limitedParallelism(2)
  private val running = Semaphore(2)
  private val requests = Array(16) { Mutex() }
  private val lock = Any()
  private data class Cached(val preview: AppleCurrentThemePreview?)
  private val cache = object : LruCache<String, Cached>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Cached): Int = maxOf(1024,
      (value.preview?.light?.allocationByteCount ?: 0) + (value.preview?.dark?.allocationByteCount ?: 0))
  }

  suspend fun load(packageName: String): AppleCurrentThemePreview? = withContext(worker) {
    if (!appleCurrentThemeSourceReady(theme, source)) return@withContext null
    val applied = source ?: return@withContext null
    requests[(packageName.hashCode() and Int.MAX_VALUE) % requests.size].withLock {
      synchronized(lock) { cache.get(packageName) }?.let { return@withContext it.preview }
      val result = running.withPermit {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val entry = appleCurrentThemeEntry(applied, packageName, componentFor(packageName))
          ?: return@withPermit null
        // Arbitrary MAML needs its live renderer; a static resource would misrepresent it.
        if (entry.nativeName in applied.dynamicNames) return@withPermit null
        val pair = loadCurrent(applied, entry)
        caller.ensureActive()
        val palette = monochromeEnabled && entry.nativeName in applied.paletteNames
        val light: Bitmap
        val dark: Bitmap?
        if (palette) {
          val tint = colors ?: return@withPermit null
          val mask = pair.lightMonochrome ?: return@withPermit null
          light = tintIcon(mask, pair.backgroundMask, tint.lightForeground, tint.lightBackground)
          dark = tintIcon(pair.darkMonochrome ?: mask, pair.backgroundMask,
            tint.darkForeground, tint.darkBackground)
        } else {
          light = pair.light ?: return@withPermit null
          dark = pair.dark
        }
        caller.ensureActive()
        val independent = dark?.takeUnless { light.sameAs(it) }
        caller.ensureActive()
        AppleCurrentThemePreview(light, independent)
      }
      currentCoroutineContext().ensureActive()
      synchronized(lock) { cache.put(packageName, Cached(result)) }
      result
    }
  }

  private fun tintIcon(mask: Bitmap, backgroundMask: Bitmap?, foreground: Int, background: Int): Bitmap {
    val size = 192
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val bounds = Rect(0, 0, size, size)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (backgroundMask != null) {
      paint.colorFilter = PorterDuffColorFilter(background, PorterDuff.Mode.SRC_IN)
      canvas.drawBitmap(backgroundMask, null, bounds, paint)
    } else {
      val shape = AdaptiveIconDrawable(ColorDrawable(android.graphics.Color.WHITE), null)
      shape.bounds = bounds
      canvas.save()
      canvas.clipPath(shape.iconMask)
      canvas.drawColor(background)
      canvas.restore()
    }
    paint.colorFilter = PorterDuffColorFilter(foreground, PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(mask, null, bounds, paint)
    return result
  }
}

/** Scope this once to the Apple screen, then pass it to the lazy card builder. */
@Composable
internal fun rememberAppleCurrentThemePreviewProvider(model: HomeViewModel): AppleCurrentThemePreviewProvider {
  val source by model.browserSource.collectAsStateWithLifecycle()
  val runtime by model.runtime.collectAsStateWithLifecycle()
  val scan by model.apps.collectAsStateWithLifecycle()
  val monet by model.monet.collectAsStateWithLifecycle()
  val configuration = Configuration(LocalConfiguration.current)
  val theme = runtime.report?.iconTheme
  val colors = remember(monet.snapshot) { previewColors(monet.snapshot) }
  val monochrome = monet.snapshot?.monochrome?.enabled == true
  return remember(model, source, theme, colors, monochrome, scan.previewGeneration, configuration) {
    AppleCurrentThemePreviewProvider(theme, source, colors, monochrome,
      componentFor = { packageName ->
        model.getApplication<android.app.Application>().getSystemService(LauncherApps::class.java)
          ?.getActivityList(packageName, Process.myUserHandle())?.firstOrNull()?.componentName?.className
      },
      loadCurrent = { applied, entry -> model.currentPreviews.load(applied, entry) })
  }
}
