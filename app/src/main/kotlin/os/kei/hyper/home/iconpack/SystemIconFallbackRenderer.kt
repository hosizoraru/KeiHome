package os.kei.hyper.home.iconpack

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import java.io.File
import java.util.zip.ZipInputStream
import os.kei.hyper.home.data.SystemIconFallback
import os.kei.hyper.home.iconpack.perfect.PerfectSystemFallback

/**
 * Native system artwork is never inferred from a package-name prefix. Explicit Perfect resources
 * can complete third-party apps too; live clock/calendar icons keep their existing renderer.
 */
internal class SystemIconFallbackRenderer(
  private val context: Context,
  private val style: SystemIconFallback,
  private val clipOutput: Boolean = true,
  perfectOpener: (String) -> PerfectSystemFallback.Source = { PerfectSystemFallback.open(context, it) },
) : java.io.Closeable {
  private val apps = mutableMapOf<String, ApplicationInfo?>()
  private val systemPackages = mutableMapOf<String, Boolean>()
  private val preinstalledPackages = mutableMapOf<String, Boolean>()
  private val nativeSources = mutableMapOf<IconPackComponent, Boolean>()
  private val defaultShape by lazy { if (clipOutput) NativeAdaptiveIconMask.load() else null }
  private val shape by lazy {
    if (!clipOutput) null else when (style) {
      SystemIconFallback.DEFAULT -> defaultShape
      SystemIconFallback.CLASSIC -> context.assets.open("system-icon-fallback/classic-mask.txt").use {
        NativeAdaptiveIconMask.fromPathData("HyperOS Classic/transform_config.xml",
          it.readNBytes(4097).toString(Charsets.UTF_8).trim())
      }
      SystemIconFallback.BOUNDLESS -> null
      else -> defaultShape
    }
  }
  private val preset by lazy {
    if (style == SystemIconFallback.DEFAULT || style.isPerfect) emptyMap() else {
      buildMap {
        context.assets.open("system-icon-fallback/${style.name.lowercase()}.zip").use { stream ->
          ZipInputStream(stream).use { zip ->
            while (true) {
              val entry = zip.nextEntry ?: break
              require(!entry.isDirectory && entry.name.endsWith(".png") && '/' !in entry.name)
              val bytes = zip.readNBytes(4 * 1024 * 1024 + 1)
              validateSource(bytes)
              put(entry.name.removeSuffix(".png"), bytes)
              require(size <= 1000)
            }
          }
        }
      }
    }
  }

  private val perfectSourceHolder = lazy { style.perfectStyle?.let(perfectOpener) }
  private val perfectSource get() = perfectSourceHolder.value
  val perfectSourceHash: String? get() = perfectSource?.sourceHash
  private val installedApps by lazy {
    context.packageManager.getInstalledApplications(
      PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())).associateBy { it.packageName }
  }

  fun completionTargets(): List<IconPackComponent> = perfectSource?.nativeNames.orEmpty().mapNotNull { name ->
    val packageName = name.substringBefore('-')
    val activity = name.substringAfter('-', "").takeIf { it.isNotEmpty() }
    IconPackComponent.parse(if (activity == null) packageName else "$packageName/$activity")
  }.distinct()

  fun validateCompletionSource() { perfectSource?.validate() }
  fun requiresLiveSource(target: IconPackComponent): Boolean = live(target)
  override fun close() {
    if (perfectSourceHolder.isInitialized()) perfectSource?.close()
  }

  fun isSystem(target: IconPackComponent): Boolean {
    if (isSystem(target.packageName)) return true
    val app = apps[target.packageName] ?: return false
    // A preload is not a system app. Admit it only to artwork fallback, with an actual stock source.
    if (!nativeSources.getOrPut(target) {
      nativeLauncherIconNames(target).any { name ->
        val root = "/product/media/theme/miui_mod_icons"
        readSource(File(root, "$name.png")) != null ||
          (readSource(File(root, "dynamic/$name/0.png")) != null &&
            readSource(File(root, "dynamic/$name/1.png")) != null)
      }
    }) return false
    return preinstalledPackages.getOrPut(target.packageName) {
      NativePreinstalledIcons.matches(context.packageManager, app)
    }
  }

  private fun isSystem(packageName: String): Boolean = systemPackages.getOrPut(packageName) {
    val app = application(packageName)
    app != null && (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
      SystemIconCompanions.matches(packageName, ::isSystem) { ui, host ->
        try { context.packageManager.checkSignatures(ui, host) == PackageManager.SIGNATURE_MATCH }
        catch (_: SecurityException) { false }
      })
  }

  private fun application(packageName: String): ApplicationInfo? =
    if (apps.containsKey(packageName)) apps[packageName] else {
      (if (style.isPerfect) installedApps[packageName] else try {
        context.packageManager.getApplicationInfo(packageName,
          PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
      } catch (_: PackageManager.NameNotFoundException) { null })
        .also { apps[packageName] = it }
    }

  fun render(target: IconPackComponent, size: Int, check: () -> Unit): Bitmap? =
    render(target, size, false, check)

  fun render(target: IconPackComponent, size: Int, darkMode: Boolean, check: () -> Unit): Bitmap? {
    check()
    if (live(target)) return null
    val names = nativeLauncherIconNames(target)
    perfectSource?.icon(names, darkMode)?.let { artwork ->
      return renderPerfect(artwork.layers, size, check)
    }
    // Perfect completion and native fallback are mutually exclusive user choices.
    if (style.isPerfect) return null
    if (!isSystem(target)) return null
    names.firstNotNullOfOrNull { preset[it] }?.let {
      return renderBitmap(it, size, shape, check)
    }
    // Stock Default layers can be newer than both the APK icon and adjacent flat PNG.
    // Compose 0..4 exactly as IconCustomizerUtils.transformToAdaptiveIcon does.
    for (name in names) {
      require(validNativeIconName(name))
      val layers = mutableListOf<Bitmap>()
      try {
        for (index in 0..4) {
          check()
          val data = readSource(File("/product/media/theme/miui_mod_icons/dynamic/$name", "$index.png"))
            ?: break
          layers += BitmapFactory.decodeByteArray(data, 0, data.size) ?: break
        }
        if (layers.size >= 2) {
          val resources = context.resources
          return renderAdaptive(AdaptiveIconDrawable(BitmapDrawable(resources, layers[0]),
            LayerDrawable(layers.drop(1).map { BitmapDrawable(resources, it) }.toTypedArray())), size, check)
        }
      } finally { layers.forEach(Bitmap::recycle) }
    }
    rawAdaptive(target)?.let { return renderAdaptive(it, size, check) }
    val data = names.firstNotNullOfOrNull { name ->
      readSource(File("/product/media/theme/miui_mod_icons", "$name.png"))
    } ?: return null
    return renderBitmap(data, size, defaultShape, check)
  }

  private fun renderPerfect(layers: List<ByteArray>, size: Int, check: () -> Unit): Bitmap {
    require(layers.size in 1..2)
    require(size in 1..2048)
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
      val bounds = Rect(0, 0, size, size)
      val layerBounds = if (layers.size == 2) NativeLayerViewport.bounds(bounds) else bounds
      val canvas = Canvas(result)
      defaultShape?.let { canvas.clipPath(it.pathForBounds(bounds)) }
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      layers.forEach { bytes ->
        check()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8192 && options.outHeight == options.outWidth)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        val decodedSize = maxOf(size, 512).coerceAtMost(2048)
        while (options.outWidth / options.inSampleSize > decodedSize) options.inSampleSize *= 2
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        try { canvas.drawBitmap(source, null, layerBounds, paint) } finally { source.recycle() }
      }
      return result
    } catch (failure: Throwable) { result.recycle(); throw failure }
  }

  private fun rawAdaptive(target: IconPackComponent): AdaptiveIconDrawable? {
    val app = apps[target.packageName] ?: return null
    val pm = context.packageManager
    return try {
      val activityIcon = target.className?.let {
        pm.getActivityInfo(android.content.ComponentName(target.packageName, it),
          PackageManager.ComponentInfoFlags.of(0)).icon
      } ?: 0
      val id = activityIcon.takeIf { it != 0 } ?: app.icon
      if (id == 0) null else
        (pm.getResourcesForApplication(app).getDrawable(id, null) as? AdaptiveIconDrawable)?.mutate()
          as? AdaptiveIconDrawable
    } catch (_: PackageManager.NameNotFoundException) { null }
      catch (_: android.content.res.Resources.NotFoundException) { null }
  }

  /** Shared raster path for native layers and APK adaptives; also exercised by edge fixtures. */
  internal fun renderAdaptive(source: AdaptiveIconDrawable, size: Int, check: () -> Unit): Bitmap {
    check()
    val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    try {
      source.setBounds(0, 0, size, size)
      Canvas(result).apply {
        defaultShape?.pathFor(source)?.let(::clipPath)
        source.draw(this)
      }
      return result
    } catch (failure: Throwable) { result.recycle(); throw failure }
  }

  private fun readSource(file: File): ByteArray? {
    if (!file.isFile || !file.canRead() || file.length() !in 1..4 * 1024 * 1024) return null
    return file.inputStream().use { it.readNBytes(4 * 1024 * 1024 + 1) }.takeIf(::sourceSupported)
  }

  private fun renderBitmap(
    data: ByteArray, size: Int, mask: NativeAdaptiveIconMask?, check: () -> Unit,
  ): Bitmap? {
    val source = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
    return try {
      check()
      val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      try {
        val bounds = Rect(0, 0, size, size)
        Canvas(result).apply {
          mask?.let { clipPath(it.pathForBounds(bounds)) }
          drawBitmap(source, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        result
      } catch (failure: Throwable) { result.recycle(); throw failure }
    } finally { source.recycle() }
  }

  private fun validateSource(bytes: ByteArray) {
    require(sourceSupported(bytes))
  }

  private fun sourceSupported(bytes: ByteArray): Boolean {
    if (bytes.size !in 24..4 * 1024 * 1024 ||
      !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    return bounds.outMimeType == "image/png" && bounds.outWidth in 1..512 && bounds.outWidth == bounds.outHeight
  }

  private fun live(target: IconPackComponent): Boolean {
    if (target.packageName == "com.xiaomi.calendar" ||
      ColorOsFallbackRenderer.dynamicReason(target.packageName, null) != null) return true
    val app = application(target.packageName) ?: return false
    val metadata = listOfNotNull(app.metaData, target.className?.let {
      try {
        context.packageManager.getActivityInfo(android.content.ComponentName(target.packageName, it),
          PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())).metaData
      } catch (_: PackageManager.NameNotFoundException) { null }
    })
    return metadata.any { ColorOsFallbackRenderer.dynamicReason(target.packageName, it) != null }
  }
}
