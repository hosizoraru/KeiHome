package os.kei.hyper.home.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.BackdropPipeline
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlurEffect

@Immutable
internal data class KeiHomeBackdropPipeline(
  val colors: BlurColors,
  val blurRadius: Float,
) : BackdropPipeline {
  override val shape: Shape get() = RectangleShape

  override fun configureEffects(scope: BackdropEffectScope) {
    with(scope) {
      textureBlurEffect(blurRadiusX = blurRadius, colors = colors)
    }
  }
}

@Composable
internal fun rememberKeiHomeBackdropPipeline(
  colors: BlurColors,
  blurRadius: Float,
): KeiHomeBackdropPipeline = remember(colors, blurRadius) {
  KeiHomeBackdropPipeline(colors = colors, blurRadius = blurRadius)
}
