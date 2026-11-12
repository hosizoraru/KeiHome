package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme

class IconPreviewShapeUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun colorAndMonetFallbackUseTheSamePublishedShape() {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
    val visible = androidx.compose.runtime.mutableStateOf(true)
    val shape = "M50,0 L100,50 L50,100 L0,50 Z"
    try {
      compose.setContent {
        MiuixTheme {
          if (visible.value) androidx.compose.foundation.layout.Row {
            Box(Modifier.background(Color.White)) {
              PreviewIcon(bitmap, null, null, Modifier.size(64.dp).testTag("color"), maskPathData = shape)
            }
            Box(Modifier.background(Color.White)) {
              PreviewIcon(bitmap, android.graphics.Color.BLUE, android.graphics.Color.GRAY,
                Modifier.size(64.dp).testTag("monet"), maskPathData = shape)
            }
          }
        }
      }
      for (tag in listOf("color", "monet")) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[0, 0])
        assertEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
      }
    } finally {
      compose.runOnIdle { visible.value = false }
      compose.waitForIdle()
      bitmap.recycle()
    }
  }
}
