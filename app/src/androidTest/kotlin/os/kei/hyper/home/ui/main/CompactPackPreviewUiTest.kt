package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.data.IconPackAppearance
import os.kei.hyper.home.iconpack.*
import os.kei.hyper.home.ui.components.IconPreviewArtworkFrame
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class CompactPackPreviewUiTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun importSharesPreviewRowAndPairsFitAtNormalAndLargeText() = cardGeometry(false)
  @Test fun applyAndUpdateRemainUsableInTheSamePreviewRow() = cardGeometry(true)

  private fun cardGeometry(imported: Boolean) {
    val scale = mutableFloatStateOf(1f)
    val entries = List(6) { IconPackEntry("sample.$it", light = IconPackArtwork.Archive(emptyList())) }
    val pack = IconPackDescriptor("preview.geometry", "Preview", IconPackFormat.INSTALLED_APK,
      IconPackSource.Installed("preview.geometry", 1, 0), entries)
    val repository = IconPackPreviewRepository(context)
    var imports = 0
    rule.setContent { KeiHomeTheme {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, scale.floatValue)) {
        Box(Modifier.width(320.dp)) {
          IconPackCard(IconPackCardData(pack, 6, entries), repository, IconPackAppearance.SYSTEM,
            nativeId = if (imported) "native" else null, current = false, importing = false,
            enabled = true, result = null, onImport = { imports++ }, onRecheck = {})
        }
      }
    } }
    for (size in listOf(1f, 1.6f, 2f)) {
      rule.runOnIdle { scale.floatValue = size }
      val strip = rule.onNodeWithTag("pack.preview.strip").fetchSemanticsNode().boundsInRoot
      val actions = rule.onNodeWithTag("pack.actions").fetchSemanticsNode().boundsInRoot
      assertTrue("Actions belong beside the preview strip", strip.right < actions.left)
      assertEquals(strip.center.y, actions.center.y, 1f)
      val pairs = rule.onAllNodesWithTag("pack.preview.pair").fetchSemanticsNodes()
      assertTrue("Keep representative pairs on a narrow window", pairs.size >= 2)
      for (pair in pairs) {
        assertTrue(pair.boundsInRoot.left >= strip.left - 1)
        assertTrue(pair.boundsInRoot.right <= strip.right + 1)
      }
      val label = context.getString(if (imported) R.string.pack_action_update_short else R.string.pack_action_import_short)
      rule.onNodeWithText(label).assertIsDisplayed().assertIsEnabled().performClick()
      if (imported) rule.onNodeWithText(context.getString(R.string.pack_action_apply_short))
        .assertIsDisplayed().assertIsEnabled()
    }
    rule.runOnIdle { assertEquals(3, imports) }
  }

  @Test fun opaqueArtworkHasNoAddedInsetsBackplateOrCornerClip() = inspectFrame(false)
  @Test fun maskedArtworkKeepsTransparentCornersInTheLargePreview() = inspectFrame(true)

  private fun inspectFrame(masked: Boolean) {
    val raw = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
      eraseColor(android.graphics.Color.MAGENTA)
    }
    val bitmap = if (masked) IconMaskPolicy.clip(raw, NativeAdaptiveIconMask.fromPathData("test.circle",
      "M50 0 A50 50 0 1 1 50 100 A50 50 0 1 1 50 0 Z")) else raw
    rule.setContent { KeiHomeTheme {
      Box(Modifier.size(96.dp).background(Color.Green).testTag("frame.canvas")) {
        IconPreviewArtworkFrame(Modifier.testTag("frame")) {
          Image(bitmap.asImageBitmap(), null, Modifier.size(96.dp).testTag("frame.image"))
        }
      }
    } }
    assertEquals(rule.onNodeWithTag("frame.image").fetchSemanticsNode().boundsInRoot,
      rule.onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot)
    val pixels = rule.onNodeWithTag("frame.canvas").captureToImage().toPixelMap()
    assertEquals(Color.Magenta.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    assertEquals((if (masked) Color.Green else Color.Magenta).toArgb(), pixels[1, 1].toArgb())
  }

  @Test fun mountedStyleThumbnailReloadsWhenRepositoryRevisionChanges() {
    val color = AtomicInteger(android.graphics.Color.MAGENTA)
    val reads = AtomicInteger()
    val builtin = BuiltinIconPack("revision.preview", "Revision preview", setOf("probe.png")) { _, _, check ->
      check()
      reads.incrementAndGet()
      val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(color.get()) }
      try {
        ByteArrayOutputStream().use { out ->
          bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
          out.toByteArray()
        }
      } finally { bitmap.recycle() }
    }
    val artwork = IconPackArtwork.Archive(
      listOf(IconArchiveVariant("probe.png", IconResourceQualifiers("drawable", densityDpi = 0))))
    val entry = IconPackEntry("probe", light = artwork, dark = artwork)
    val pack = IconPackDescriptor("revision.preview", "Revision preview", IconPackFormat.MIUI_ICONS,
      IconPackSource.Builtin(builtin), listOf(entry))
    val repository = IconPackPreviewRepository(context)
    rule.setContent { KeiHomeTheme {
      Box(Modifier.size(48.dp).testTag("thumbnail")) {
        PackStyleThumbnail(pack, entry, repository, IconPackAppearance.SYSTEM)
      }
    } }
    fun centerColor(): Int {
      val pixels = rule.onNodeWithTag("thumbnail").captureToImage().toPixelMap()
      return pixels[pixels.width / 2, pixels.height / 2].toArgb()
    }
    rule.waitUntil(10_000) { reads.get() > 0 && centerColor() == Color.Magenta.toArgb() }
    val before = reads.get()
    color.set(android.graphics.Color.CYAN)
    rule.runOnIdle { repository.clear() }
    rule.waitUntil(10_000) { reads.get() > before && centerColor() == Color.Cyan.toArgb() }
  }
}
