package os.kei.hyper.home.xposed

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.util.WeakHashMap

/** Identity hints, never an authority over an explicit package request. */
internal class AppIconIdentityRegistry(private val limit: Int = 192) {
  private data class Image(val name: String, val generation: Int)
  private val drawables = WeakHashMap<Drawable, String>()
  private val bitmaps = WeakHashMap<Bitmap, Image>()

  @Synchronized fun remember(drawable: Drawable, name: String) {
    if (drawables.size >= limit && drawable !in drawables) drawables.clear()
    val old = drawables[drawable]
    drawables[drawable] = if (old == null || old == name) name else ""
  }

  /** Call only for renderer-owned snapshots, not shared native loading placeholders. */
  @Synchronized fun remember(bitmap: Bitmap, name: String) {
    if (bitmaps.size >= limit && bitmap !in bitmaps) bitmaps.clear()
    val old = bitmaps[bitmap]
    val generation = bitmap.generationId
    bitmaps[bitmap] = Image(
      if (old == null || old.generation != generation || old.name == name) name else "", generation)
  }

  @Synchronized fun find(drawable: Drawable?, bitmap: Bitmap?): String? =
    drawables[drawable]?.takeIf { it.isNotEmpty() }
      ?: bitmaps[bitmap]?.takeIf { bitmap != null && it.generation == bitmap.generationId }
        ?.name?.takeIf { it.isNotEmpty() }

  @Synchronized fun clear() { drawables.clear(); bitmaps.clear() }
}
