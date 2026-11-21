package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable

/** Import/export worker only. Both the declarative program and original pixels must be proven. */
internal object NativeMamlMonet {
  class Artwork(
    val light: ByteArray, val dark: ByteArray, val foreground: Bitmap, val background: Bitmap,
  ) : Closeable {
    override fun close() { foreground.recycle(); background.recycle() }
  }

  fun read(entry: IconPackEntry, check: () -> Unit, bytes: (String) -> ByteArray): Artwork? {
    val day = entry.light as? IconPackArtwork.Archive ?: return null
    val night = entry.dark as? IconPackArtwork.Archive ?: return null
    if (!day.palettePair || !night.palettePair || day.variants.size != 1 || night.variants.size != 1 ||
      day.part != night.part) return null
    val dayPath = day.variants.single().path
    val nightPath = night.variants.single().path
    val root = "fancy_icons/${entry.nativeName ?: return null}/"
    if (dayPath == nightPath || dayPath.substringBeforeLast('/') + "/" != root ||
      nightPath.substringBeforeLast('/') + "/" != root) return null
    check()
    val lightBytes = bytes(dayPath)
    val darkBytes = bytes(nightPath)
    if (lightBytes.contentEquals(darkBytes)) return null
    fun decode(data: ByteArray): Bitmap? {
      if (data.size > 4 * 1024 * 1024) return null
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
      if (bounds.outWidth !in 32..512 || bounds.outWidth != bounds.outHeight) return null
      // Eligibility is optional: unsupported PNG metadata must not prevent lossless native export.
      try { GeneratedIconPng.squareSize(data) }
      catch (_: IllegalArgumentException) { return null }
      catch (_: IllegalStateException) { return null }
      return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
        inScaled = false
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
      })
    }
    val light = decode(lightBytes) ?: return null
    try {
      val dark = decode(darkBytes) ?: return null
      try {
        if (light.width != dark.width || light.height != dark.height) return null
        val dayPixels = IntArray(light.width * light.height)
        val nightPixels = IntArray(dayPixels.size)
        light.getPixels(dayPixels, 0, light.width, 0, 0, light.width, light.height)
        dark.getPixels(nightPixels, 0, dark.width, 0, 0, dark.width, dark.height)
        val masks = NativeTwoToneMask.derive(light.width, light.height, dayPixels, nightPixels, check) ?: return null
        val foreground = Bitmap.createBitmap(masks.foregroundPixels, light.width, light.height, Bitmap.Config.ARGB_8888)
        try {
          val background = Bitmap.createBitmap(masks.backgroundPixels, light.width, light.height, Bitmap.Config.ARGB_8888)
          return Artwork(lightBytes, darkBytes, foreground, background)
        } catch (failure: Throwable) { foreground.recycle(); throw failure }
      } finally { dark.recycle() }
    } finally { light.recycle() }
  }
}
