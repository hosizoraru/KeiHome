package os.kei.hyper.home.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.main.ForegroundComposeHostRule
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

class KeiHomeBackdropPipelineDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun equalValuesProduceEqualPublicBackdropModifiersAndChangedInputsDoNot() {
    val first = KeiHomeBackdropPipeline(colors(Color.White), 10f)
    val equal = KeiHomeBackdropPipeline(colors(Color.White), 10f)
    assertNotSame(first.colors, equal.colors)
    assertEquals(first, equal)
    assertEquals(first.hashCode(), equal.hashCode())
    val original = Modifier.drawBackdrop(PatternBackdrop, pipeline = first)
    assertEquals(original, Modifier.drawBackdrop(PatternBackdrop, pipeline = equal))
    assertEquals(original.hashCode(), Modifier.drawBackdrop(PatternBackdrop, pipeline = equal).hashCode())
    assertNotEquals(original, Modifier.drawBackdrop(PatternBackdrop,
      pipeline = first.copy(colors = colors(Color.Black))))
    assertNotEquals(original, Modifier.drawBackdrop(PatternBackdrop,
      pipeline = first.copy(blurRadius = 18f)))
  }

  @Test fun rememberSurvivesUnrelatedRecompositionAndRefreshesColorsAndRadius() {
    var unrelated by mutableIntStateOf(0)
    var surface by mutableStateOf(Color.White)
    var radius by mutableStateOf(10f)
    lateinit var observed: KeiHomeBackdropPipeline
    var compositions = 0
    compose.setContent {
      val pipeline = rememberKeiHomeBackdropPipeline(
        BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.8f)))),
        radius)
      val description = unrelated.toString()
      SideEffect { observed = pipeline; compositions++ }
      Box(Modifier.size(32.dp).semantics { stateDescription = description })
    }
    compose.waitForIdle()
    val original = observed
    val before = compositions
    compose.runOnIdle { unrelated++ }
    compose.runOnIdle {
      assertTrue(compositions > before)
      assertSame(original, observed)
      surface = Color(0xff252d39)
    }
    compose.runOnIdle {
      assertNotSame(original, observed)
      assertEquals(colors(surface), observed.colors)
    }
    val recolored = observed
    compose.runOnIdle { radius = 18f }
    compose.runOnIdle {
      assertNotSame(recolored, observed)
      assertEquals(18f, observed.blurRadius, 0f)
    }
  }

  @Test fun renderedPixelsMatchLegacyTextureBlurAcrossColorRadiusAndDensityUpdates() {
    assertTrue("Runtime shaders must be available for pixel parity", isRuntimeShaderSupported())
    var surface by mutableStateOf(Color(0xfff4f1ea))
    var radius by mutableStateOf(10f)
    var density by mutableStateOf(1f)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density)) {
        val colors = BlurDefaults.blurColors(
          blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.8f))))
        val pipeline = rememberKeiHomeBackdropPipeline(colors, radius)
        Row {
          Box(Modifier.size(96.dp, 64.dp).testTag("pipeline")
            .drawBackdrop(PatternBackdrop, pipeline = pipeline))
          Box(Modifier.size(96.dp, 64.dp).testTag("legacy")
            .textureBlur(PatternBackdrop, RectangleShape, blurRadius = radius, colors = colors))
        }
      }
    }
    val initial = assertLegacyParity()
    compose.runOnIdle { surface = Color(0xff252d39) }
    val dark = assertLegacyParity()
    assertFalse("Changed surface must reach rendered pixels", initial.contentEquals(dark))
    compose.runOnIdle { surface = Color(0xff384b2a) }
    val recolored = assertLegacyParity()
    assertFalse("Changed palette must reach rendered pixels", dark.contentEquals(recolored))
    compose.runOnIdle { radius = 18f }
    val widerBlur = assertLegacyParity()
    assertFalse("Changed radius must reach rendered pixels", recolored.contentEquals(widerBlur))
    compose.runOnIdle { density = 2f }
    val denser = assertLegacyParity()
    assertEquals(initial.size * 4, denser.size)
  }

  private fun colors(surface: Color) =
    BlurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha = 0.8f))))

  private fun assertLegacyParity(): IntArray {
    compose.waitForIdle()
    val actual = pixels("pipeline")
    val legacy = pixels("legacy")
    assertEquals(legacy.size, actual.size)
    for (index in actual.indices) {
      for (shift in listOf(0, 8, 16, 24)) {
        val difference = kotlin.math.abs(
          ((actual[index] ushr shift) and 255) - ((legacy[index] ushr shift) and 255))
        assertTrue("Pixel $index channel $shift differed by $difference", difference <= 2)
      }
    }
    return actual
  }

  private fun pixels(tag: String): IntArray {
    val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    return IntArray(bitmap.width * bitmap.height).also {
      bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
  }

  private object PatternBackdrop : Backdrop {
    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
      density: Density,
      coordinates: LayoutCoordinates?,
      layerBlock: (GraphicsLayerScope.() -> Unit)?,
      downscaleFactor: Float,
    ) {
      drawRect(Color(0xff1678bd))
      drawRect(Color(0xffd53628), topLeft = Offset(size.width / 2f, 0f),
        size = Size(size.width / 2f, size.height))
    }
  }
}
