package os.kei.hyper.home.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.data.PreservedIcons
import os.kei.hyper.home.iconpack.CustomIconMask
import os.kei.hyper.home.iconpack.IconMaskPolicy
import os.kei.hyper.home.iconpack.IconPackPreviewRepository
import os.kei.hyper.home.iconpack.OriginalAppIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MaskComparisonPreservationUiTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()

  @Test fun preservedSampleStillShowsOriginalOs4AndCustomPackMasks() {
    MaskPreviewPolicyFixture().use { fixture ->
      fixture.writePublished(MaskPreviewPolicyFixture.SQUARE, android.graphics.Color.BLUE)
      val component = requireNotNull(fixture.entry.component)
      assertTrue(component.packageName in PreservedIcons.snapshot(fixture.context))
      val path = MaskPreviewPolicyFixture.CIRCLE
      val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        .joinToString("") { "%02x".format(it) }
      val policies = listOf(
        IconMaskPolicy(false),
        IconMaskPolicy.hyperOs4Preview(),
        IconMaskPolicy(true, CustomIconMask(path, "Circle", digest)),
      )
      val repository = IconPackPreviewRepository(fixture.context)
      val visible = mutableStateOf(true)
      try {
        compose.setContent {
          MiuixTheme {
            if (visible.value) Row {
              policies.forEachIndexed { index, policy ->
                MaskComparisonIcon(fixture.pack, fixture.entry, repository, policy,
                  false, "Comparison $index", 0, Modifier.testTag("comparison-$index"))
              }
            }
          }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
          policies.indices.all { index ->
            val pixels = compose.onNodeWithTag("comparison-$index").captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2] == Color.Red
          }
        }
        val stage = Color(0xfff0f1f4)
        assertSample(0, 0.08f, Color.Red)
        assertSample(1, 0.08f, stage)
        assertSample(1, 0.14f, Color.Red)
        assertSample(2, 0.14f, stage)
        // Comparison must not change the saved preservation selection or the normal renderer.
        assertTrue(component.packageName in PreservedIcons.snapshot(fixture.context))
        runBlocking(Dispatchers.IO) {
          val ordinary = repository.load(fixture.pack, fixture.entry, maskPolicy = policies[0])
          val expected = requireNotNull(OriginalAppIcons.render(fixture.context,
            component, 128, false))
          try { assertTrue(requireNotNull(ordinary.light).sameAs(expected)) }
          finally { expected.recycle() }
        }
      } finally {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
      }
    }
  }

  private fun assertSample(index: Int, fraction: Float, expected: Color) {
    val pixels = compose.onNodeWithTag("comparison-$index").captureToImage().toPixelMap()
    assertEquals(expected, pixels[(pixels.width * fraction).toInt(), (pixels.height * fraction).toInt()])
  }
}
