package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

internal object ArchiveLayerRenderer {
  /** Source-over in declared order on a transparent canvas; no background or masking is inferred. */
  fun render(
    artwork: IconPackArtwork.Archive,
    dark: Boolean,
    densityDpi: Int,
    sdk: Int,
    sizePx: Int,
    check: () -> Unit,
    decode: (IconArchiveVariant) -> Bitmap?,
  ): Bitmap? {
    require(sizePx in 1..512)
    val groups = artwork.layers.ifEmpty { listOf(artwork.variants) }
    require(groups.size in 1..NativeLayerConfig.MAX_LAYERS)
    val selected = groups.map { selectArchiveVariant(it, dark, densityDpi, sdk) ?: return null }
    if (selected.any { !it.isRaster }) throw IconPackException("Uncompiled drawable XML requires an APK resource table")
    if (selected.size == 1 && !artwork.nativeLayers) { check(); return decode(selected.single()) }
    val result = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    try {
      val canvas = Canvas(result)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
      val output = Rect(0, 0, sizePx, sizePx)
      val rect = if (artwork.nativeLayers) NativeLayerViewport.bounds(output) else output
      for (variant in selected) {
        check()
        val layer = decode(variant) ?: run { result.recycle(); return null }
        try { canvas.drawBitmap(layer, null, rect, paint) } finally { layer.recycle() }
      }
      return result
    } catch (failure: Throwable) {
      result.recycle()
      throw failure
    }
  }
}
