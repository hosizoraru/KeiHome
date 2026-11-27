package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.util.WeakHashMap
import os.kei.hyper.home.data.AppDisplayStorage

/** One immutable worker-thread snapshot shared by preview and publication. */
internal data class IconMaskPolicy(
  val enabled: Boolean,
  val custom: CustomIconMask? = null,
) {
  val identity: String get() = when {
    !enabled -> "original"
    custom != null -> "custom-${custom.digest}"
    else -> DEFAULT_IDENTITY
  }
  val defaultPath: String get() = NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA

  fun complete(original: ByteArray?, legacyStyle: Boolean = false): ByteArray? =
    if (!enabled || legacyStyle) original
    else NativeIconShapeConfig.complete(original, defaultPath, custom?.pathData)

  fun resolve(original: ByteArray?, legacyStyle: Boolean = false): NativeAdaptiveIconMask? =
    fromConfig(complete(original, legacyStyle))

  companion object {
    const val DEFAULT_IDENTITY = "hyperos4-v1"

    fun hyperOs4Preview(): IconMaskPolicy {
      val path = NativeAdaptiveIconMask.HYPER_OS4_PATH_DATA
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        .joinToString("") { "%02x".format(it) }
      return IconMaskPolicy(true, CustomIconMask(path, "HyperOS4", digest))
    }

    /** Called once on a worker, never once per bitmap or from composition. */
    fun snapshot(context: Context, enabled: Boolean? = null): IconMaskPolicy {
      val active = enabled ?: AppDisplayStorage.open(context).getBoolean("complete_system_icon_config", true)
      return IconMaskPolicy(active, if (active) CustomIconMaskStore(context).readNow() else null)
    }

    /** Applied themes read their own published XML, without pending private settings. */
    fun fromConfig(bytes: ByteArray?): NativeAdaptiveIconMask? =
      NativeIconShapeConfig.fullMask(bytes)?.let { path ->
        runCatching { NativeAdaptiveIconMask.fromPathData("icon-mask", path) }.getOrNull()
      }

    private class MaskCoverage(val alpha: IntArray?)
    private val coverages = WeakHashMap<NativeAdaptiveIconMask, LinkedHashMap<Long, MaskCoverage>>()

    /** Alpha-only intersection; never adds a background or brightens edge pixels. */
    fun clip(bitmap: Bitmap, mask: NativeAdaptiveIconMask?): Bitmap {
      if (mask == null) return bitmap
      val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
      output.density = bitmap.density
      val canvas = Canvas(output)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG)
      canvas.drawPath(mask.pathForBounds(Rect(0, 0, bitmap.width, bitmap.height)), paint)
      paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
      canvas.drawBitmap(bitmap, 0f, 0f, paint)
      paint.xfermode = null
      return output
    }

    /**
     * Clips [bitmap] to [mask] only when at least one pixel exceeds [mask]'s alpha coverage.
     * Already-shaped artwork (circles, authored transparent bounds, pre-masked PNGs, matching
     * adaptive icons) passes through unchanged without re-multiplying antialiased edges.
     * When clipping is required, [bitmap] is recycled and the clipped bitmap is returned.
     */
    fun clipIfExceeds(
      bitmap: Bitmap,
      mask: NativeAdaptiveIconMask?,
      check: () -> Unit = {},
    ): Bitmap {
      if (mask == null) return bitmap
      check()
      val alpha = coverage(mask, bitmap.width, bitmap.height, check) ?: return bitmap
      val pixels = IntArray(bitmap.width * bitmap.height)
      bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      if (pixels.indices.none { Color.alpha(pixels[it]) > alpha[it] }) return bitmap
      check()
      return clip(bitmap, mask).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun coverage(
      mask: NativeAdaptiveIconMask,
      width: Int,
      height: Int,
      check: () -> Unit,
    ): IntArray? {
      val sizeKey = (width.toLong() shl 32) or (height.toLong() and 0xFFFF_FFFFL)
      synchronized(coverages) {
        coverages[mask]?.get(sizeKey)?.let { return it.alpha }
      }
      check()
      val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val alpha = try {
        Canvas(rendered).drawPath(mask.pathForBounds(Rect(0, 0, width, height)),
          Paint(Paint.ANTI_ALIAS_FLAG))
        IntArray(width * height).also { values ->
          rendered.getPixels(values, 0, width, 0, 0, width, height)
          for (i in values.indices) values[i] = Color.alpha(values[i])
        }.takeUnless { values -> values.all { it == 255 } }
      } finally { rendered.recycle() }
      check()
      synchronized(coverages) {
        if (coverages.size >= 8 && !coverages.containsKey(mask)) {
          coverages.keys.firstOrNull()?.let(coverages::remove)
        }
        val bySize = coverages.getOrPut(mask) { LinkedHashMap(4, 0.75f, true) }
        if (bySize.size >= 4 && !bySize.containsKey(sizeKey)) {
          bySize.keys.firstOrNull()?.let(bySize::remove)
        }
        bySize[sizeKey] = MaskCoverage(alpha)
      }
      return alpha
    }
  }
}
