package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4CategoryTabs
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class Os4SegmentedTabsDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun nativeTouchesKeepBothChoiceSizesAndCategoriesBoundedAcrossThemesAndLargeFonts() {
    var dark by mutableStateOf(false)
    var large by mutableStateOf(false)
    var overlay by mutableStateOf(false)
    var view by mutableIntStateOf(0)
    var preview by mutableIntStateOf(2)
    var category by mutableIntStateOf(0)
    val viewEvents = mutableListOf<Int>()
    val previewEvents = mutableListOf<Int>()
    val categoryEvents = mutableListOf<Int>()
    val views = listOf("Grid", "Sliders")
    val previews = listOf("Light", "Dark", "Compare")
    val categories = listOf("Primary", "Secondary", "Tertiary", "Neutral", "Variant")
    lateinit var screenshots: File
    rule.setContent {
      screenshots = LocalContext.current.cacheDir
      val density = LocalDensity.current
      val controller = remember(dark) {
        ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
      }
      CompositionLocalProvider(LocalKeiHomeDarkTheme provides dark,
        LocalDensity provides Density(density.density, if (large) 1.6f else 1f)) {
        MiuixTheme(controller = controller) {
          val surface = MiuixTheme.colorScheme.surface
          val source = rememberLayerBackdrop { drawRect(surface); drawContent() }
          val selectors: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
              Os4ViewTabs(views, view, { view = it; viewEvents += it }, Modifier.testTag("segmented.views"))
              Os4ViewTabs(previews, preview, { preview = it; previewEvents += it },
                Modifier.testTag("segmented.preview"))
              Os4CategoryTabs(categories, category, { category = it; categoryEvents += it },
                Modifier.testTag("segmented.categories"))
            }
          }
          KeiHomeScaffold {
            Box(Modifier.fillMaxSize()) {
              Box(Modifier.fillMaxSize().layerBackdrop(source)
                .background(Brush.verticalGradient(listOf(Color(0xFF278DC4), surface)))) {
                if (!overlay) selectors()
              }
              if (overlay) CompositionLocalProvider(LocalKeiHomeBackdrop provides source) { selectors() }
            }
          }
        }
      }
    }
    for (night in listOf(false, true)) for (enlarged in listOf(false, true))
      for (inOverlay in listOf(false, true)) {
      val previous = listOf(view, preview, category)
      rule.runOnIdle { dark = night; large = enlarged; overlay = inOverlay }
      rule.waitForIdle()
      rule.runOnIdle { assertEquals(previous, listOf(view, preview, category)) }
      exerciseRow("segmented.views", views, viewEvents) { view }
      exerciseRow("segmented.preview", previews, previewEvents) { preview }
      exerciseRow("segmented.categories", categories, categoryEvents) { category }
      assertLabelsFitVertically(views + previews + categories)
      val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
      File(screenshots, "os4-segmented-$night-$enlarged-$inOverlay.png").outputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    }
  }

  @Test fun cancelledTouchAndChangingAvailableViewsDoNotInventSelectionCallbacks() {
    var items by mutableStateOf(listOf("Light", "Dark", "Compare"))
    var selected by mutableIntStateOf(2)
    val selections = mutableListOf<Int>()
    rule.setContent {
      MiuixTheme {
        Os4ViewTabs(items, selected, { selected = it; selections += it },
          Modifier.padding(16.dp).testTag("segmented.dynamic"))
      }
    }
    rule.onNodeWithText("Light").performScrollTo().performTouchInput { down(center); cancel() }
    rule.runOnIdle { assertEquals(2, selected); assertTrue(selections.isEmpty()) }
    rule.onNodeWithText("Compare").assertIsSelected()
    rule.runOnIdle { items = listOf("Light", "Dark") }
    rule.onNodeWithText("Dark").assertIsSelected().performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(2, selected); assertTrue(selections.isEmpty()) }
    rule.onNodeWithText("Light").performTouchInput { click(center) }
    rule.runOnIdle { assertEquals(listOf(0), selections); assertEquals(0, selected) }
    rule.runOnIdle { items = emptyList() }
    rule.onAllNodesWithTag("segmented.dynamic").assertCountEquals(0)
    rule.runOnIdle { assertEquals(listOf(0), selections) }
  }

  private fun exerciseRow(tag: String, labels: List<String>, events: List<Int>, selected: () -> Int) {
    val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    for (index in labels.indices.reversed()) {
      val before = selected()
      val count = events.size
      val tab = rule.onNodeWithText(labels[index])
      tab.performScrollTo()
      tab.assertIsDisplayed()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
      tab.performTouchInput { click(center) }
      rule.waitForIdle()
      tab.assertIsSelected()
      rule.runOnIdle {
        assertEquals(index, selected())
        assertEquals(count + if (index == before) 0 else 1, events.size)
      }
      assertEquals(bounds, rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
      val tabBounds = tab.fetchSemanticsNode().boundsInRoot
      assertTrue("Tab ${labels[index]} must stay inside $tag",
        tabBounds.left >= bounds.left - 1f && tabBounds.right <= bounds.right + 1f &&
          tabBounds.top >= bounds.top - 1f && tabBounds.bottom <= bounds.bottom + 1f)
    }
  }

  private fun assertLabelsFitVertically(labels: List<String>) {
    for (label in labels) {
      val layouts = mutableListOf<TextLayoutResult>()
      rule.onNodeWithText(label, useUnmergedTree = true)
        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
      assertTrue("Missing text layout for $label", layouts.isNotEmpty())
      val layout = layouts.single()
      assertFalse("Large text must not be clipped vertically: $label; size=${layout.size}, " +
        "paragraphHeight=${layout.multiParagraph.height}, fontScale=${layout.layoutInput.density.fontScale}",
        layout.didOverflowHeight)
      assertFalse("The native single-line label must not be ellipsized: $label", layout.isLineEllipsized(0))
    }
  }
}
