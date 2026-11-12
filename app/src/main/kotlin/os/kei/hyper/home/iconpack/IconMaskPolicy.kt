package os.kei.hyper.home.iconpack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
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
  }
}
