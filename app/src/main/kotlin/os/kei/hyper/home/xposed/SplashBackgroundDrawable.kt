package os.kei.hyper.home.xposed

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity

internal object SplashBackgroundDrawable {
  fun backgroundColor(drawable: Drawable?): Int? = base(drawable)?.color

  fun adapt(drawable: Drawable, night: Boolean): Drawable {
    val color = backgroundColor(drawable) ?: return drawable
    val adapted = SplashBackgroundPolicy.adapt(color, night)
    if (adapted == color) return drawable
    val state = drawable.constantState ?: return drawable
    val copy = state.newDrawable().mutate()
    if (copy === drawable || backgroundColor(copy) != color) return drawable
    copyProperties(drawable, copy)
    val background = base(copy) ?: return drawable
    background.color = adapted
    return copy
  }

  private fun base(drawable: Drawable?): ColorDrawable? {
    if (drawable == null || drawable.alpha != 255 || drawable.colorFilter != null) return null
    val color = when (drawable.javaClass) {
      ColorDrawable::class.java -> drawable as ColorDrawable
      LayerDrawable::class.java -> {
        val layers = drawable as LayerDrawable
        if (layers.numberOfLayers == 0 ||
          layers.getLayerWidth(0) != -1 || layers.getLayerHeight(0) != -1 ||
          layers.getLayerInsetLeft(0) != 0 || layers.getLayerInsetTop(0) != 0 ||
          layers.getLayerInsetRight(0) != 0 || layers.getLayerInsetBottom(0) != 0 ||
          layers.getLayerInsetStart(0) !in listOf(0, LayerDrawable.INSET_UNDEFINED) ||
          layers.getLayerInsetEnd(0) !in listOf(0, LayerDrawable.INSET_UNDEFINED) ||
          layers.getLayerGravity(0) !in listOf(Gravity.NO_GRAVITY, Gravity.FILL)) return null
        val first = layers.getDrawable(0) ?: return null
        if (first.javaClass != ColorDrawable::class.java) return null
        first as ColorDrawable
      }
      else -> return null
    }
    return color.takeIf { it.alpha == 255 && it.colorFilter == null }
  }

  private fun copyProperties(source: Drawable, target: Drawable) {
    target.changingConfigurations = source.changingConfigurations
    target.isAutoMirrored = source.isAutoMirrored
    target.layoutDirection = source.layoutDirection
    target.state = source.state.clone()
    target.level = source.level
    target.bounds = source.bounds
    target.setVisible(source.isVisible, false)
    if (source is LayerDrawable && target is LayerDrawable) {
      for (index in 0 until source.numberOfLayers) {
        val child = source.getDrawable(index) ?: continue
        val copiedChild = target.getDrawable(index) ?: continue
        copyProperties(child, copiedChild)
      }
    } else {
      if (target.alpha != source.alpha) target.alpha = source.alpha
      if (source.colorFilter != null) target.colorFilter = source.colorFilter
    }
  }
}
