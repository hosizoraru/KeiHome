package os.kei.hyper.home.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.UserManager
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import os.kei.hyper.home.xposed.IconSupportStatus
import os.kei.hyper.home.xposed.MonochromeMaskQuality
import os.kei.hyper.home.xposed.StableIconResourceGraph
import os.kei.hyper.home.iconpack.NativeAdaptiveIconMask
import os.kei.hyper.home.iconpack.OriginalIconShape
import os.kei.hyper.home.iconpack.IconMaskPolicy

/** Shared snapshots: callers must not mutate or recycle them; eviction and clear only drop references. */
internal data class AppIconPreviews(
  val light: Bitmap? = null,
  val dark: Bitmap? = null,
  val lightMonochrome: Bitmap? = null,
  val darkMonochrome: Bitmap? = null,
  val componentName: ComponentName? = null,
  val backgroundMask: Bitmap? = null,
  /** Shape of the published theme, for clipping after monochrome recoloring. */
  val maskPathData: String? = null,
) {
  val lightMonochromeFallback: Boolean get() = lightMonochrome == null
  val darkMonochromeFallback: Boolean get() = darkMonochrome == null
}

/** Call load from the item's coroutine; cancellation stays with that caller, without a private scope. */
internal class IconPreviewRepository(
  context: Context,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val application = context.applicationContext
  private val hostResources = context.resources
  private val renderer = dispatcher.limitedParallelism(2)
  private val lock = Any()
  private var generation = 0L
  private var defaultPreview: Pair<Pair<Int, String?>, AppIconPreviews>? = null
  private val cache = object : LruCache<PreviewKey, CachedPreview>(4 * 1024 * 1024) {
    override fun sizeOf(key: PreviewKey, value: CachedPreview): Int =
      value.previews.let { listOf(it.light, it.dark, it.lightMonochrome, it.darkMonochrome) }
        .sumOf { it?.allocationByteCount ?: 0 }
  }

  suspend fun load(app: InstalledIconCapability, sizePx: Int = 128,
    colorMask: NativeAdaptiveIconMask? = null): AppIconPreviews {
    require(sizePx > 0)
    val requestedGeneration = synchronized(lock) { generation }
    return withContext(renderer) {
      val caller = currentCoroutineContext()
      caller.ensureActive()
      val mask = colorMask ?: OriginalIconShape.mask
      val resolved = previewOrNull { resolve(app, sizePx) } ?: return@withContext AppIconPreviews()
      val target = resolved.copy(key = resolved.key.copy(maskPath = mask?.pathData))
      if (target.key.iconId == 0) {
        val key = sizePx to mask?.pathData
        synchronized(lock) { defaultPreview?.takeIf { it.first == key }?.second }?.let { return@withContext it }
        val icon = drawIconBitmap(application.packageManager.defaultActivityIcon.mutate(), sizePx, mask)
        return@withContext AppIconPreviews(icon, icon, maskPathData = mask?.pathData)
          .also { synchronized(lock) { defaultPreview = key to it } }
      }
      val cached = synchronized(lock) {
        if (generation == requestedGeneration) cache.get(target.key) else null
      }
      if (cached != null && cached.resourcesAreCurrent()) return@withContext cached.previews

      fun resources(night: Int): Resources? {
        caller.ensureActive()
        return previewOrNull {
          application.packageManager.getResourcesForApplication(
            target.applicationInfo,
            Configuration(target.key.configuration).apply {
              uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            },
          )
        }
      }
      // No Resources/AssetManager creation on a cache hit.
      val lightResources = resources(Configuration.UI_MODE_NIGHT_NO)
      val darkResources = resources(Configuration.UI_MODE_NIGHT_YES)

      fun render(resources: Resources?): IconModePreview {
        caller.ensureActive()
        return resources?.let {
          previewOrNull { renderIconPreview(it, target.key.iconId, sizePx,
            colorMask = mask) { caller.ensureActive() } }
        } ?: IconModePreview(null, null)
      }
      val light = render(lightResources)
      val dark = render(darkResources)
      val result = AppIconPreviews(
        light.color, dark.color, light.monochrome, dark.monochrome, target.key.component,
        maskPathData = mask?.pathData,
      )
      synchronized(lock) {
        caller.ensureActive()
        if (generation == requestedGeneration && light.color != null && dark.color != null &&
          lightResources != null && darkResources != null
        ) {
          cache.put(target.key, CachedPreview(result, lightResources, darkResources))
        }
      }
      result
    }
  }

  fun sizeBytes(): Int = synchronized(lock) { cache.size() }

  fun clear() = synchronized(lock) {
    generation++
    cache.evictAll()
    defaultPreview = null
  }

  internal fun invalidatePackages(targets: Set<PackageRefreshTarget>) = synchronized(lock) {
    generation++
    cache.snapshot().keys.filter { key ->
      targets.any { it.matches(key.component.packageName, key.userSerial) }
    }.forEach(cache::remove)
  }

  private fun resolve(app: InstalledIconCapability, sizePx: Int): Target? {
    // InstalledIconCapability.userId is a persistent SERIAL, not UserHandle.identifier.
    val user = application.getSystemService(UserManager::class.java)
      ?.getUserForSerialNumber(app.userId) ?: return null
    val entry = application.getSystemService(LauncherApps::class.java)
      ?.getActivityList(app.packageName, user)?.firstOrNull()
    // The scanner groups a package's entries and uses its first entry's label. Preserve that order.
    val info = entry?.activityInfo
    val appInfo = info?.applicationInfo ?: application.packageManager.getApplicationInfo(app.packageName, 0)
    val iconId = info?.iconResource ?: appInfo.icon
    val base = Configuration(hostResources.configuration).apply {
      uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
    }
    val applicationInfo = appInfo
    val paths = (listOfNotNull(applicationInfo.sourceDir, applicationInfo.publicSourceDir) +
      applicationInfo.splitSourceDirs.orEmpty().toList() +
      applicationInfo.splitPublicSourceDirs.orEmpty().toList()).distinct()
    val files = paths.map { path -> File(path).let { ApkStamp(path, it.lastModified(), it.length()) } }
    return Target(
      PreviewKey(
        app.userId, entry?.componentName ?: ComponentName(app.packageName, app.packageName), app.versionCode, iconId, sizePx, files, base,
        hostResources.displayMetrics.densityDpi,
      ),
      applicationInfo,
    )
  }

  private data class ApkStamp(val path: String, val modified: Long, val length: Long)

  private data class PreviewKey(
    val userSerial: Long,
    val component: ComponentName,
    val version: Long,
    val iconId: Int,
    val size: Int,
    val apks: List<ApkStamp>,
    val configuration: Configuration,
    val density: Int,
    val maskPath: String? = null,
  )

  private data class Target(val key: PreviewKey, val applicationInfo: ApplicationInfo)

  private class CachedPreview(
    val previews: AppIconPreviews,
    private val light: Resources,
    private val dark: Resources,
  ) {
    private val lightAssets = light.assets
    private val darkAssets = dark.assets

    // Observe framework replacement of an existing resource/overlay asset set without creating
    // new resources on hits. Retain the Resources as well as the resulting published bitmaps.
    fun resourcesAreCurrent(): Boolean = light.assets === lightAssets && dark.assets === darkAssets
  }
}

internal data class IconModePreview(val color: Bitmap?, val monochrome: Bitmap?)

/** Synchronous rendering, called only on the repository's bounded background dispatcher. */
internal fun renderIconPreview(
  resources: Resources,
  iconId: Int,
  sizePx: Int,
  colorMask: NativeAdaptiveIconMask? = null,
  checkCancelled: () -> Unit = {},
): IconModePreview {
  val icon = resources.getDrawableForDensity(iconId, resources.displayMetrics.densityDpi, null)
    ?.mutate() ?: return IconModePreview(null, null)
  icon.setLayoutDirection(resources.configuration.layoutDirection)
  val color = drawIconBitmap(icon, sizePx, colorMask)
  checkCancelled()
  val monochrome = previewOrNull {
    val adaptive = icon as? AdaptiveIconDrawable ?: return@previewOrNull null
    val layer = adaptive.monochrome ?: return@previewOrNull null
    if (layer is Animatable || layer.canApplyTheme() ||
      StableIconResourceGraph(resources, resources).monochromeStatus(iconId) != IconSupportStatus.DETECTED
    ) return@previewOrNull null

    // Inspect the un-clipped layer at the inspector's 96px size: adaptive clipping must not
    // disguise a flat opaque layer or a padded full-color bitmap as a usable monochrome glyph.
    val layerBounds = Rect(layer.bounds)
    val sample = drawIconBitmap(layer, 96)
    val unusable = try {
      val pixels = IntArray(96 * 96)
      sample.getPixels(pixels, 0, 96, 0, 0, 96, 96)
      MonochromeMaskQuality.isUnusable(pixels, 96, 96, bitmap = layer is BitmapDrawable)
    } finally {
      sample.recycle()
      layer.bounds = layerBounds
    }
    if (unusable) return@previewOrNull null
    checkCancelled()

    // AdaptiveIconDrawable.draw() fills its layer buffer black. Draw ONLY the real mono layer
    // onto transparent pixels, retaining the adaptive child bounds and the device icon mask.
    val rendered = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(rendered)
      canvas.clipPath(adaptive.iconMask)
      layer.draw(canvas)
      checkCancelled()
      rendered.extractAlpha()
    } finally {
      rendered.recycle()
    }
  }
  return IconModePreview(color, monochrome)
}

private fun drawIconBitmap(drawable: Drawable, size: Int, mask: NativeAdaptiveIconMask? = null): Bitmap {
  val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
  try {
    drawable.setBounds(0, 0, size, size)
    drawable.draw(Canvas(bitmap))
    return IconMaskPolicy.clip(bitmap, mask).also { if (it !== bitmap) bitmap.recycle() }
  } catch (failure: Exception) {
    bitmap.recycle() // This bitmap has never been published.
    throw failure
  }
}

private inline fun <T> previewOrNull(block: () -> T): T? = try {
  block()
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (_: Exception) {
  null
}
