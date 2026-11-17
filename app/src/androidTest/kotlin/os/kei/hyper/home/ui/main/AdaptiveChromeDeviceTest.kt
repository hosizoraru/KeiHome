package os.kei.hyper.home.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.R
import os.kei.hyper.home.ui.components.KeiHomeAdaptiveNavigation
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.Os4FloatingToolbar
import os.kei.hyper.home.ui.components.Os4LazyColumn
import os.kei.hyper.home.ui.components.Os4ViewTabs
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class AdaptiveChromeDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()

  @Test fun resizeAndRailTogglePreserveSelectionContentAndScroll() {
    var width by mutableStateOf(900.dp)
    var selected by mutableStateOf(0)
    var origin by mutableStateOf(0)
    val originEvents = mutableListOf<Int>()
    var creations = 0
    lateinit var list: LazyListState
    lateinit var scope: CoroutineScope
    rule.setContent {
      Fixture {
        Box(Modifier.size(width, 700.dp)) {
          KeiHomeAdaptiveNavigation(destinations(), selected, { selected = it }, null) { wide ->
            remember { creations++ }
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            Column {
              Os4ViewTabs(listOf("All 200", "User 49", "System 151"), origin,
                { origin = it; originEvents += it })
              Os4LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                item { Text(if (wide) "Wide content" else "Compact content") }
                items(200, key = { it }) { Text("Row $it", Modifier.height(56.dp)) }
              }
            }
          }
        }
      }
    }
    rule.onNodeWithText("Apps").performTouchInput { click() }
    rule.runOnIdle { assertEquals(1, selected); scope.launch { list.scrollToItem(60) } }
    rule.waitForIdle()
    rule.runOnIdle { assertEquals(60, list.firstVisibleItemIndex); width = 500.dp }
    rule.onNodeWithTag("adaptive.rail").assertDoesNotExist()
    rule.runOnIdle { assertEquals(60, list.firstVisibleItemIndex); assertEquals(1, creations); width = 900.dp }
    rule.onNodeWithTag("adaptive.rail").assertIsDisplayed()
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    rule.onNodeWithContentDescription(context.getString(R.string.navigation_collapse))
      .performTouchInput { click() }
    rule.waitForIdle()
    assertTrue(rule.onNodeWithTag("os4.list.scrollbar").fetchSemanticsNode().boundsInRoot.top >= 60f)
    rule.onNodeWithContentDescription(context.getString(R.string.navigation_expand))
      .performTouchInput { click() }
    rule.runOnIdle {
      assertEquals(60, list.firstVisibleItemIndex)
      assertEquals(1, selected)
      assertEquals(1, creations)
      assertEquals(0, origin)
      assertTrue(originEvents.isEmpty())
    }
  }

  @Test fun railReservesTheCorrectSideWithLargeFontAndBothThemes() {
    var rtl by mutableStateOf(false)
    var night by mutableStateOf(false)
    rule.setContent {
      Fixture(rtl = rtl, night = night, font = 1.6f) {
        Box(Modifier.size(900.dp, 700.dp)) {
          KeiHomeAdaptiveNavigation(destinations(), 0, {}, null) {
            Box(Modifier.fillMaxSize().testTag("body")) { Text("Content") }
          }
        }
      }
    }
    for (direction in listOf(false, true)) for (dark in listOf(false, true)) {
      rule.runOnIdle { rtl = direction; night = dark }
      rule.onNodeWithText("Settings").assertIsDisplayed()
      val body = rule.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
      if (direction) assertTrue(body.right <= 570f) else assertTrue(body.left >= 330f)
      assertTrue(body.width >= 550f)
    }
  }

  @Test fun collapsedNavigationAndListPositionSurviveSavedStateRestoration() {
    val restoration = StateRestorationTester(rule)
    lateinit var list: LazyListState
    lateinit var scope: CoroutineScope
    var observedSelection = 0
    restoration.setContent {
      Fixture {
        var selected by rememberSaveable { mutableStateOf(0) }
        observedSelection = selected
        Box(Modifier.size(900.dp, 700.dp)) {
          KeiHomeAdaptiveNavigation(destinations(), selected, { selected = it }, null) {
            list = rememberLazyListState()
            scope = rememberCoroutineScope()
            Os4LazyColumn(list, Modifier.fillMaxSize()) {
              items(200) { Text("Row $it", Modifier.height(56.dp)) }
            }
          }
        }
      }
    }
    rule.onNodeWithText("Apps").performTouchInput { click() }
    rule.runOnIdle { scope.launch { list.scrollToItem(60) } }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    rule.onNodeWithContentDescription(context.getString(R.string.navigation_collapse))
      .performTouchInput { click() }
    rule.waitForIdle()
    restoration.emulateSavedInstanceStateRestore()
    rule.onNodeWithContentDescription(context.getString(R.string.navigation_expand)).assertIsDisplayed()
    rule.runOnIdle {
      assertEquals(1, observedSelection)
      assertEquals(60, list.firstVisibleItemIndex)
    }
  }

  @Test fun shortWindowsUseCompactNavigationEvenWhenWide() {
    rule.setContent {
      Fixture {
        Box(Modifier.size(900.dp, 420.dp)) {
          KeiHomeAdaptiveNavigation(destinations(), 0, {}, null) { wide ->
            Text(if (wide) "Wide" else "Compact")
          }
        }
      }
    }
    rule.onNodeWithText("Compact").assertIsDisplayed()
    rule.onNodeWithTag("adaptive.rail").assertDoesNotExist()
  }

  @Test fun rootOverlayInheritsBackdropAndBlocksRailAndContentInBothLayouts() {
    var width by mutableStateOf(900.dp)
    var overlayShown by mutableStateOf(true)
    var sampling by mutableStateOf(true)
    var seen: Backdrop? = null
    var expected: Backdrop? = null
    var selected by mutableStateOf(0)
    var overlayClicks = 0
    var contentClicks = 0
    rule.setContent {
      Fixture {
        val source = rememberLayerBackdrop()
        expected = source.takeIf { sampling }
        Box(Modifier.size(width, 700.dp)) {
          KeiHomeAdaptiveNavigation(destinations(), selected, { selected = it }, expected,
            overlay = {
              seen = LocalKeiHomeBackdrop.current
              if (overlayShown) Box(Modifier.fillMaxSize()
                .clickable { overlayClicks++ }.testTag("root.overlay"))
            }) {
            Box(Modifier.fillMaxSize().clickable { contentClicks++ })
          }
        }
      }
    }
    for (window in listOf(900.dp, 500.dp)) for (enabled in listOf(true, false)) {
      rule.runOnIdle { width = window; sampling = enabled }
      assertEquals(window.value,
        rule.onNodeWithTag("root.overlay").fetchSemanticsNode().boundsInRoot.width, 1f)
      rule.onNodeWithTag("root.overlay").performTouchInput { click(Offset(80f, 90f)) }
      rule.runOnIdle {
        assertSame(expected, seen)
        assertEquals(0, selected)
        assertEquals(0, contentClicks)
      }
    }
    rule.runOnIdle {
      assertEquals(4, overlayClicks)
      width = 900.dp
      overlayShown = false
    }
    rule.onNodeWithText("Apps").performTouchInput { click() }
    rule.runOnIdle { assertEquals(1, selected) }
  }

  @Test fun scrollbarDragsSharedListWithoutStealingTrailingActions() {
    lateinit var list: LazyListState
    lateinit var scope: CoroutineScope
    var taps = 0
    rule.setContent {
      Fixture {
        list = rememberLazyListState()
        scope = rememberCoroutineScope()
        Os4LazyColumn(list, Modifier.size(400.dp, 600.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 40.dp, bottom = 70.dp)) {
          items(200, key = { it }) { index ->
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp)) {
              Text("Row $index", Modifier.weight(1f))
              Text("Action $index", Modifier.clickable { taps++ }.padding(8.dp))
            }
          }
        }
      }
    }
    rule.onNodeWithTag("os4.list.scrollbar").performTouchInput {
      swipe(start = center.copy(y = 50f), end = center.copy(y = height - 100f), durationMillis = 600)
    }
    rule.runOnIdle { assertTrue(list.firstVisibleItemIndex > 30); scope.launch { list.scrollToItem(50) } }
    rule.onNodeWithText("Action 51").performClick()
    rule.runOnIdle { assertEquals(1, taps) }
  }

  @Test fun shortListsDoNotAddAnInactiveScrollbar() {
    rule.setContent {
      Fixture {
        Os4LazyColumn(rememberLazyListState(), Modifier.size(400.dp, 600.dp)) {
          item { Text("Only row", Modifier.height(56.dp)) }
        }
      }
    }
    rule.onNodeWithText("Only row").assertIsDisplayed()
    rule.onNodeWithTag("os4.list.scrollbar").assertDoesNotExist()
  }

  @Test fun compactTitleAndWideSurfacesRespectTheirOwnBounds() {
    var compact by mutableStateOf(true)
    rule.setContent {
      Fixture {
        Box(Modifier.size(1100.dp, 700.dp).background(Color.Blue).testTag("bounded.frame")) {
          KeiHomeScaffold(containerColor = Color.Red, topBar = {
            KeiHomeTopAppBar("Tools", expandable = !compact, modifier = Modifier.testTag("bar"))
          }) {
            Box(Modifier.fillMaxSize().testTag("bounded.body")) {
              Os4FloatingToolbar(modifier = Modifier.fillMaxWidth().testTag("toolbar")) { Text("Actions") }
            }
          }
        }
      }
    }
    val short = rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.height
    assertEquals(1040f, rule.onNodeWithTag("bounded.body").fetchSemanticsNode().boundsInRoot.width, 1f)
    assertTrue(rule.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot.width <= 641f)
    val pixels = rule.onNodeWithTag("bounded.frame").captureToImage().toPixelMap()
    assertEquals(Color.Red.toArgb(), pixels[10, 350].toArgb())
    assertEquals(Color.Red.toArgb(), pixels[1090, 350].toArgb())
    rule.runOnIdle { compact = false }
    assertTrue(rule.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot.height > short + 30f)
  }

  @Composable private fun Fixture(
    rtl: Boolean = false,
    night: Boolean = false,
    font: Float = 1f,
    content: @Composable () -> Unit,
  ) {
    val controller = remember(night) {
      ThemeController(colorSchemeMode = if (night) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    CompositionLocalProvider(
      LocalDensity provides Density(1f, font),
      LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
      LocalKeiHomeDarkTheme provides night,
    ) { MiuixTheme(controller = controller) { content() } }
  }

  private fun destinations() = listOf("Status", "Apps", "Icons", "Desktop", "Settings")
    .map { NavigationItem(it, MiuixIcons.Glass.Info) }
}
