package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.abs
import kotlin.math.roundToInt

class OnboardingChromeTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun scrolledCardFadesAcrossTheHeaderInsteadOfEndingAtAHardStrip() {
    val dark = mutableStateOf(true)
    val card = Color(0xFF3987C9)
    rule.setContent {
      val controller = remember(dark.value) {
        ThemeController(colorSchemeMode = if (dark.value) ColorSchemeMode.Dark else ColorSchemeMode.Light)
      }
      MiuixTheme(controller = controller) {
        OnboardingPageLayout(
          modifier = Modifier.testTag("chrome.page"),
          header = { Box(Modifier.fillMaxWidth().height(96.dp).testTag("chrome.header")) },
          footer = { Box(Modifier.fillMaxWidth().height(80.dp)) },
        ) { clearance ->
          Column(Modifier.fillMaxSize().testTag("chrome.scroll")
            .verticalScroll(rememberScrollState()).padding(clearance)) {
            Box(Modifier.fillMaxWidth().height(2000.dp).background(card))
          }
        }
      }
    }
    rule.onNodeWithTag("chrome.scroll").performSemanticsAction(SemanticsActions.ScrollBy) {
      it(0f, with(rule.density) { 300.dp.toPx() })
    }
    for (night in listOf(true, false)) {
      rule.runOnIdle { dark.value = night }
      rule.waitForIdle()
      val header = rule.onNodeWithTag("chrome.header").fetchSemanticsNode().boundsInRoot
      val page = rule.onNodeWithTag("chrome.page")
      val bounds = page.fetchSemanticsNode().boundsInRoot
      val pixels = page.captureToImage().toPixelMap()
      val x = pixels.width / 2
      val edge = (header.bottom - bounds.top).roundToInt()
      val density = rule.density.density
      fun distanceAt(y: Int): Float {
        val pixel = pixels[x, y]
        return abs(pixel.red - card.red) + abs(pixel.green - card.green) + abs(pixel.blue - card.blue)
      }
      val above = distanceAt((edge - 16 * density).roundToInt())
      val below = distanceAt((edge + 16 * density).roundToInt())
      assertTrue("The card must remain visible under the header in dark=$night", above > .05f && above < 1.5f)
      assertTrue("Content must fade gradually across the header boundary in dark=$night", above > below + .05f)
      assertTrue("No solid background strip may end at the header in dark=$night",
        abs(distanceAt(edge - 1) - distanceAt(edge + 1)) < .08f)
      assertTrue("The unobscured card must retain its original color in dark=$night",
        distanceAt((edge + 32 * density).roundToInt()) < .03f)
    }
  }
}
