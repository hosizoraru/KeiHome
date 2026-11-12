package os.kei.hyper.home.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.iconpack.IconPackPreviews
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.NativeIconMaskImport
import top.yukonga.miuix.kmp.theme.MiuixTheme

class AppliedPackThumbnailPolicyUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun usesPublishedArtworkDespitePendingMaskAndPreservedPackage() {
    MaskPreviewPolicyFixture().use { fixture ->
      val pending = runBlocking(Dispatchers.IO) { IconMaskPolicy.snapshot(fixture.context) }
      assertEquals(NativeIconMaskImport.canonicalPath(MaskPreviewPolicyFixture.SQUARE),
        pending.custom?.pathData)
      val fallbackLoads = AtomicInteger()
      val repository = CurrentIconPreviewRepository(fixture.context, { _, _ ->
        fallbackLoads.incrementAndGet()
        IconPackPreviews()
      })
      val visible = mutableStateOf(true)
      try {
        compose.setContent {
          MiuixTheme {
            if (visible.value) AppliedPackThumbnail(fixture.source(), fixture.entry, repository,
              fixture.theme(), 0, Modifier.background(Color.White).testTag("applied"))
          }
        }
        awaitColor(Color.Blue)
        assertPixels(Color.Blue, Color.White)
        assertEquals("Published static artwork must bypass prospective rendering", 0, fallbackLoads.get())
        fixture.writePending(MaskPreviewPolicyFixture.CIRCLE)
        repository.clear()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        awaitColor(Color.Blue)
        assertPixels(Color.Blue, Color.White)
      } finally {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
      }
    }
  }

  @Test fun reloadsForGenerationThemeAndConfigurationAndHidesMismatchedSnapshot() {
    MaskPreviewPolicyFixture().use { fixture ->
      val repository = CurrentIconPreviewRepository(fixture.context, { _, _ -> IconPackPreviews() })
      val source = mutableStateOf(fixture.source())
      val theme = mutableStateOf(fixture.theme())
      val generation = mutableStateOf(0)
      val night = mutableStateOf(false)
      val visible = mutableStateOf(true)
      try {
        compose.setContent {
          val configuration = Configuration(LocalConfiguration.current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
              if (night.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
          }
          CompositionLocalProvider(LocalConfiguration provides configuration) {
            MiuixTheme {
              if (visible.value) AppliedPackThumbnail(source.value, fixture.entry, repository,
                theme.value, generation.value, Modifier.background(Color.White).testTag("applied"))
            }
          }
        }
        awaitColor(Color.Blue)
        fixture.writePublished(MaskPreviewPolicyFixture.SQUARE, android.graphics.Color.RED)
        repository.clear()
        compose.runOnIdle { generation.value++ }
        awaitColor(Color.Red)
        assertPixels(Color.Red, Color.Red)

        compose.runOnIdle { theme.value = fixture.theme("replacement") }
        awaitColor(Color.White)
        assertPixels(Color.White, Color.White)
        compose.runOnIdle { source.value = fixture.source("replacement") }
        awaitColor(Color.Red)

        fixture.writePublished(MaskPreviewPolicyFixture.CIRCLE, android.graphics.Color.GREEN)
        repository.clear()
        compose.runOnIdle { night.value = true }
        awaitColor(Color.Green)
        assertPixels(Color.Green, Color.White)
      } finally {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
      }
    }
  }

  private fun awaitColor(expected: Color) {
    compose.waitUntil(timeoutMillis = 10_000) {
      val pixels = compose.onNodeWithTag("applied").captureToImage().toPixelMap()
      pixels[pixels.width / 2, pixels.height / 2] == expected
    }
  }

  private fun assertPixels(center: Color, corner: Color) {
    val pixels = compose.onNodeWithTag("applied").captureToImage().toPixelMap()
    assertTrue(pixels.width > 0 && pixels.height > 0)
    assertEquals(center, pixels[pixels.width / 2, pixels.height / 2])
    assertEquals(corner, pixels[pixels.width / 10, pixels.height / 10])
  }
}
