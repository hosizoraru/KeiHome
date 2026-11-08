package os.kei.hyper.home.iconpack

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveLayerRendererTest {
  @Test fun staticZeroThenOneUsesSourceOverAlphaAndRetainsTransparentPixels() {
    val artwork = artwork()
    val result = requireNotNull(ArchiveLayerRenderer.render(artwork, false, 480, 37, 4, {}) { variant ->
      Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
        if (variant.path.endsWith("0.png")) setPixel(1, 1, Color.argb(128, 255, 0, 0))
        else {
          setPixel(1, 1, Color.argb(128, 0, 0, 255))
          setPixel(2, 1, Color.argb(128, 0, 0, 255))
        }
      }
    })
    try {
      assertEquals(0, Color.alpha(result.getPixel(0, 0)))
      val overlap = result.getPixel(1, 1)
      assertEquals(192, Color.alpha(overlap))
      assertEquals(85f, Color.red(overlap).toFloat(), 1f)
      assertEquals(170f, Color.blue(overlap).toFloat(), 1f)
      assertEquals(0, Color.green(overlap))
      assertEquals(128, Color.alpha(result.getPixel(2, 1)))
      assertEquals(255, Color.blue(result.getPixel(2, 1)))
    } finally { result.recycle() }
  }

  @Test fun layerOneDoesNotBecomeDarkAppearance() {
    val artwork = artwork()
    fun render(dark: Boolean) = requireNotNull(
      ArchiveLayerRenderer.render(artwork, dark, 480, 37, 4, {}) { variant ->
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
          eraseColor(if (variant.path.endsWith("0.png")) Color.RED else Color.BLUE)
        }
      })
    val light = render(false)
    val dark = render(true)
    try {
      assertTrue(light.sameAs(dark))
      assertEquals(Color.BLUE, light.getPixel(1, 1))
    } finally { light.recycle(); dark.recycle() }
  }

  @Test fun incompatibleOrMissingLayerReturnsNoPartialComposite() {
    val artwork = artwork().copy(layers = listOf(artwork().layers[0], emptyList()))
    var decoded = false
    assertNull(ArchiveLayerRenderer.render(artwork, false, 480, 37, 4, {}) {
      decoded = true
      null
    })
    assertFalse(decoded)
  }

  private fun artwork(): IconPackArtwork.Archive {
    val layers = (0..1).map { layer ->
      listOf(IconArchiveVariant("res/drawable-xxhdpi/com.example/$layer.png",
        IconResourceQualifiers("drawable-xxhdpi", densityDpi = 480)))
    }
    return IconPackArtwork.Archive(layers.flatten(), layers = layers)
  }
}
