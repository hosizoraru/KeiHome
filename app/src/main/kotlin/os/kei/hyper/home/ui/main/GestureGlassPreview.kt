package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import os.kei.hyper.home.xposed.GestureGlassFinish
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Draw the same optical finish at gesture-handle scale over a small two-tone material sample. */
@Composable
internal fun GestureGlassPreview(enabled: Boolean) {
  val finish = remember { GestureGlassFinish() }
  val surface = MiuixTheme.colorScheme.surface
  val darkPage = surface.luminance() < .45f
  val shade = lerp(surface, Color(0xff535389), if (darkPage) .46f else .36f)
  val light = lerp(surface, Color(0xff82b7ce), if (darkPage) .68f else .63f)
  val crest = lerp(light, Color.White, if (darkPage) .26f else .43f)
  Spacer(Modifier.fillMaxWidth().height(56.dp).drawWithCache {
    val center = Offset(size.width / 2, size.height / 2)
    val pill = Size(minOf(120.dp.toPx(), size.width * .7f), 5.dp.toPx())
    val topLeft = Offset(center.x - pill.width / 2, center.y - pill.height / 2)
    val radius = CornerRadius(pill.height / 2)
    val capsule = Path().apply { addRoundRect(RoundRect(Rect(topLeft, pill), radius)) }
    // The curved light band crosses the capsule, giving the eye a continuous reference on both sides.
    val scene = Brush.radialGradient(0f to light, .69f to light, .87f to crest, .96f to shade,
      1f to shade, center = Offset(center.x - size.height, -size.height * .9f), radius = size.height * 1.85f)
    finish.update(topLeft.x.roundToInt(), topLeft.y.roundToInt(), (topLeft.x + pill.width).roundToInt(),
      (topLeft.y + pill.height).roundToInt(), radius.x, if (darkPage) 0f else 1f, 1f)
    onDrawBehind {
      drawRect(scene)
      if (enabled) {
        clipPath(capsule) {
          scale(1.09f, 1.32f, pivot = center) { drawRect(scene) }
        }
        drawIntoCanvas { finish.draw(it.nativeCanvas) }
      } else {
        drawRoundRect(if (darkPage) Color.White else Color.Black, topLeft, pill, radius)
      }
    }
  })
}
