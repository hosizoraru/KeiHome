package os.kei.hyper.home.ui.main

import android.os.SystemClock
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.MonochromeIconSettings
import os.kei.hyper.home.data.MonetPaletteSnapshot
import os.kei.hyper.home.data.MonetPaletteSource
import os.kei.hyper.home.data.MonetReadState
import os.kei.hyper.home.data.WallpaperColorSnapshot
import os.kei.hyper.home.status.FrameworkSnapshot
import os.kei.hyper.home.status.NativeRuntimeState
import os.kei.hyper.home.status.RuntimeStatusReport
import os.kei.hyper.home.status.RuntimeStatusUiState
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import androidx.compose.foundation.layout.Box

class StatusDetailSheetTest {
  private val backHost = Os4NavigationTestHost()
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val framework = FrameworkSnapshot("Test framework", 102, true, null, null)

  @Test fun runtimeSheetDoesNotExpandThePageAndStillExpiresWhileOpen() {
    val report = RuntimeStatusReport("test", 0, BuildConfig.VERSION_CODE.toLong(),
      SystemClock.elapsedRealtime() - RuntimeStatusReport.FRESHNESS_MS + 6_000L,
      NativeRuntimeState.READY, launcherPid = 123, detail = "Test diagnostic")
    rule.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold {
            Box(Modifier.verticalScroll(rememberScrollState())) {
              StatusBody(true, framework, false, null, false, {}, {},
                runtime = RuntimeStatusUiState(report = report))
            }
          }
        }
      }
    }
    val card = rule.onNodeWithTag("status.runtime.card")
    card.performScrollTo()
    val bounds = card.fetchSemanticsNode().boundsInRoot
    card.performClick()
    rule.onNodeWithTag("status.runtime.sheet").assertIsDisplayed()
    rule.onNodeWithText("Test diagnostic").assertExists()
    assertAligned("status.runtime.overview", "status.runtime.scope", R.string.dashboard_system_scope)
    assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
    val ready = context.getString(R.string.dashboard_pill_launcher) + " · " +
      context.getString(R.string.dashboard_pill_ready)
    val expired = context.getString(R.string.dashboard_pill_launcher) + " · " +
      context.getString(R.string.runtime_expired)
    rule.onNodeWithTag("status.runtime.launcher.sheet").assertTextEquals(ready)
    rule.waitUntil(8_000) {
      runCatching {
        rule.onNodeWithTag("status.runtime.launcher.sheet").assertTextEquals(expired); true
      }.getOrDefault(false)
    }
    rule.runOnIdle { backHost.completeBack() }
    rule.waitUntil(5_000) {
      runCatching { rule.onNodeWithTag("status.runtime.sheet").assertDoesNotExist(); true }.getOrDefault(false)
    }
    assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
  }

  @Test fun monetSheetTracksReadFailuresAndNavigatesOnlyAfterExit() {
    var failed by mutableStateOf(false)
    var opened = 0
    rule.setContent {
      backHost.Provide {
        KeiHomeTheme {
          KeiHomeScaffold {
            Box(Modifier.verticalScroll(rememberScrollState())) {
              StatusBody(true, framework, false, null, failed, {}, { opened++ })
            }
          }
        }
      }
    }
    rule.onNodeWithTag("status.monet.card").performScrollTo().performClick()
    rule.onNodeWithTag("status.monet.sheet").assertIsDisplayed()
    mapOf("source" to R.string.monet_seed_source, "style" to R.string.dashboard_style,
      "colors" to R.string.monet_palette).forEach { (tag, title) ->
      assertAligned("status.monet.overview", "status.monet.$tag", title)
    }
    rule.runOnIdle { failed = true }
    rule.onNodeWithTag("status.monet.palette.sheet").assertExists()
    val failedPill = context.getString(R.string.dashboard_pill_palette) + " · " +
      context.getString(R.string.monet_state_unavailable)
    rule.onNodeWithTag("status.monet.palette.sheet").assertTextEquals(failedPill)
    rule.runOnIdle { failed = false }
    rule.onNodeWithTag("status.monet.palette.sheet").assertTextEquals(
      context.getString(R.string.dashboard_state_pill, context.getString(R.string.dashboard_pill_palette),
        context.getString(R.string.status_unknown)))
    val click = rule.onNodeWithTag("status.monet.settings").performScrollTo()
      .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    rule.runOnIdle {
      click()
      click()
      assertEquals(0, opened)
    }
    rule.waitUntil(5_000) { opened == 1 }
    rule.onNodeWithTag("status.monet.sheet").assertDoesNotExist()
  }

  @Test fun largeFontKeepsLongDetailValuesBelowTheirLabels() {
    val style = "Custom palette style with an unusually long description that must remain readable"
    val palette = MonetPaletteSource("Test palette", MonetReadState.NOT_PROVIDED, emptyList())
    val snapshot = MonetPaletteSnapshot("HOME_WALLPAPER", style,
      WallpaperColorSnapshot(MonetReadState.NOT_PROVIDED, emptyList()),
      palette, palette, MonochromeIconSettings(false, null))
    rule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
        backHost.Provide {
          KeiHomeTheme {
            KeiHomeScaffold {
              Box(Modifier.verticalScroll(rememberScrollState())) {
                StatusBody(true, framework, false, snapshot, false, {}, {})
              }
            }
          }
        }
      }
    }
    rule.onNodeWithTag("status.monet.card").performScrollTo().performClick()
    val value = rule.onNodeWithText(style).performScrollTo().assertIsDisplayed()
      .fetchSemanticsNode().boundsInRoot
    val label = rule.onNodeWithText(context.getString(R.string.dashboard_style))
      .fetchSemanticsNode().boundsInRoot
    assertTrue("The value must follow the label without overlap", value.top >= label.bottom)
    assertEquals(label.left, value.left, 1f)
    assertTrue("The long value should wrap instead of being truncated", value.height > label.height)
    rule.onNodeWithTag("status.monet.settings").performScrollTo().assertIsDisplayed()
  }

  private fun assertAligned(overview: String, detail: String, title: Int) {
    val header = rule.onNodeWithTag(overview).fetchSemanticsNode().boundsInRoot
    val row = rule.onNodeWithTag(detail).fetchSemanticsNode().boundsInRoot
    assertEquals(header.left, row.left, 1f)
    assertEquals(header.right, row.right, 1f)
    val label = rule.onNodeWithText(context.getString(title)).fetchSemanticsNode().boundsInRoot
    assertEquals("The actual text must share the overview's left edge", header.left, label.left, 1f)
  }
}
