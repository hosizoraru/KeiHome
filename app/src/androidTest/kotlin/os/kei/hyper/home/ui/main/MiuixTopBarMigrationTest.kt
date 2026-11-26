package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.KeiHomeTopAppBar
import os.kei.hyper.home.ui.components.KeiHomeIconButton
import os.kei.hyper.home.ui.components.LocalGlassTopBarScrolled
import os.kei.hyper.home.ui.components.Os4ViewTabs
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MiuixTopBarMigrationTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()

  @Test fun sharedToolbarKeepsActionGapsAtCollapsedTitleWithLargeTextAndRtl() {
    val direction = mutableStateOf(LayoutDirection.Ltr)
    val scale = mutableStateOf(1f)
    lateinit var state: TopAppBarState
    var clicks = 0
    var pixelsPerDp = 1f
    rule.setContent {
      val density = LocalDensity.current
      pixelsPerDp = density.density
      CompositionLocalProvider(LocalDensity provides Density(density.density, scale.value),
        LocalLayoutDirection provides direction.value) {
        KeiHomeTheme {
          state = rememberTopAppBarState()
          Box(Modifier.width(360.dp)) {
            KeiHomeTopAppBar(title = "完美图标补全计划", scrollBehavior = MiuixScrollBehavior(state),
              navigationIcon = { KeiHomeIconButton(onClick = {}) { Text("<") } },
              actions = {
                repeat(3) { index ->
                  KeiHomeIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("spaced-action-$index")) {
                    Text("$index")
                  }
                }
              })
          }
        }
      }
    }
    for (font in listOf(1f, 1.6f)) for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
      rule.runOnIdle { scale.value = font; direction.value = layout; state.heightOffset = state.heightOffsetLimit }
      rule.waitForIdle()
      val bounds = (0..2).map { rule.onNodeWithTag("spaced-action-$it").fetchSemanticsNode().boundsInRoot }.sortedBy { it.left }
      bounds.zipWithNext().forEach { (left, right) ->
        assertTrue("Action targets need an 8dp gap", right.left - left.right >= 8f * pixelsPerDp - 1f)
      }
      (0..2).forEach { rule.onNodeWithTag("spaced-action-$it").performClick() }
    }
    rule.runOnIdle { assertEquals(12, clicks) }
  }

  @Test fun collapsedGlassTitleAndSubtitleStayTogetherWhenSpaceIsLimited() = checkSubtitleGroup(glass = true)

  @Test fun collapsedStandardTitleAndSubtitleStayTogetherWhenSpaceIsLimited() = checkSubtitleGroup(glass = false)

  private fun checkSubtitleGroup(glass: Boolean) {
    val direction = mutableStateOf(LayoutDirection.Ltr)
    val fontScale = mutableStateOf(1f)
    lateinit var state: TopAppBarState
    var clicks = 0
    rule.setContent {
      val density = LocalDensity.current
      val dark = fontScale.value > 1f
      val controller = remember(dark) {
        ThemeController(colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
      }
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value),
        LocalLayoutDirection provides direction.value, LocalKeiHomeDarkTheme provides dark) {
        MiuixTheme(controller = controller) {
          state = rememberTopAppBarState()
          val behavior = MiuixScrollBehavior(state)
          val navigation = @androidx.compose.runtime.Composable {
            GlassIconButton(onClick = {}, modifier = Modifier.testTag("leading")) { Text("‹") }
          }
          val actions: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            GlassIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("action")) { Text("+") }
            GlassIconButton(onClick = {}, modifier = Modifier.testTag("secondary")) { Text("⋯") }
          }
          Box(Modifier.width(360.dp)) {
            if (glass) {
              GlassTopAppBar(title = "Perfect Icons", largeTitle = "Expanded heading",
                subtitle = "Backup and cache", scrollBehavior = behavior,
                navigationIcon = navigation, actions = actions)
            } else {
              TopAppBar(title = "Perfect Icons", largeTitle = "Expanded heading",
                subtitle = "Backup and cache", scrollBehavior = behavior,
                navigationIcon = navigation, actions = actions)
            }
          }
        }
      }
    }
    for (layout in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
      for (scale in listOf(1f, 1.6f)) {
        rule.runOnIdle { direction.value = layout; fontScale.value = scale }
        rule.waitForIdle()
        rule.runOnIdle { state.heightOffset = state.heightOffsetLimit; state.contentOffset = -1_000f }
        rule.waitForIdle()
        val title = rule.onNodeWithText("Perfect Icons", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val leading = rule.onNodeWithTag("leading").fetchSemanticsNode().boundsInRoot
        val trailing = rule.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
        // The expanded subtitle is also composed; select the one directly below the collapsed title.
        val subtitle = rule.onAllNodesWithText("Backup and cache", useUnmergedTree = true)
          .fetchSemanticsNodes().map { it.boundsInRoot }.minBy { abs(it.top - title.bottom) }
        val titleStart = if (layout == LayoutDirection.Ltr) title.left else title.right
        val subtitleStart = if (layout == LayoutDirection.Ltr) subtitle.left else subtitle.right
        assertTrue("Title and subtitle share a center or a leading edge",
          abs(title.center.x - subtitle.center.x) <= 2f || abs(titleStart - subtitleStart) <= 2f)
        assertEquals("Center the whole group against controls", leading.center.y,
          (title.top + subtitle.bottom) / 2, 2f)
        assertEquals("Subtitle follows the title without overlap", title.bottom, subtitle.top, 2f)
        val left = if (layout == LayoutDirection.Ltr) leading.right else trailing.right
        val right = if (layout == LayoutDirection.Ltr) trailing.left else leading.left
        assertTrue("Text must clear both controls", title.left >= left - 1 && title.right <= right + 1)
        assertTrue("Subtitle must clear both controls", subtitle.left >= left - 1 && subtitle.right <= right + 1)
        rule.onNodeWithTag("action").performClick()
      }
    }
    rule.runOnIdle { assertEquals(4, clicks) }
  }

  @Test fun appTopBarKeepsBothActionsReachableWithALongTitle() {
    var clicks = 0
    rule.setContent {
      KeiHomeTheme {
        val state = rememberTopAppBarState()
        val behavior = MiuixScrollBehavior(state)
        Box(Modifier.width(320.dp)) {
          KeiHomeTopAppBar(title = "完美图标补全计划", scrollBehavior = behavior,
            actions = {
              GlassIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("help")) { Text("?") }
              GlassIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("refresh")) { Text("+") }
            })
        }
      }
    }
    rule.onNodeWithTag("help").performClick()
    rule.onNodeWithTag("refresh").performClick()
    rule.runOnIdle { assertEquals(2, clicks) }
  }

  @Test fun topBarBottomContentTabsTrackScrollStateWithoutSelfSampling() {
    lateinit var state: TopAppBarState
    var selected by mutableIntStateOf(0)
    var observedScrolled: Boolean? = null
    rule.setContent {
      KeiHomeTheme {
        val backdrop = rememberLayerBackdrop { drawContent() }
        state = rememberTopAppBarState()
        val behavior = MiuixScrollBehavior(state)
        KeiHomeScaffold(
          modifier = Modifier.fillMaxSize(),
          backdrop = backdrop,
          topBar = {
            KeiHomeTopAppBar(
              title = "Apps",
              scrollBehavior = behavior,
              bottomContent = {
                observedScrolled = LocalGlassTopBarScrolled.current
                Os4ViewTabs(listOf("All", "User", "System"), selected, { selected = it },
                  Modifier.testTag("topbar.bottom.tabs"))
              },
            )
          },
        ) {
          Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color(0xFF278DC4)))
        }
      }
    }
    rule.waitForIdle()
    rule.runOnIdle { assertFalse("Tabs inside an unscrolled top bar start at rest", observedScrolled == true) }
    val resting = rule.onNodeWithTag("topbar.bottom.tabs").captureToImage()
    assertTrue(resting.width > 0 && resting.height > 0)
    rule.runOnIdle { state.contentOffset = -120f; state.heightOffset = state.heightOffsetLimit }
    rule.waitForIdle()
    rule.runOnIdle { assertTrue("Tabs inside a scrolled top bar enable Glass material", observedScrolled == true) }
    val scrolled = rule.onNodeWithTag("topbar.bottom.tabs").captureToImage()
    assertTrue(scrolled.width > 0 && scrolled.height > 0)
    rule.onNodeWithText("User").performClick().assertIsSelected()
    rule.runOnIdle { assertEquals(1, selected) }
  }
}
