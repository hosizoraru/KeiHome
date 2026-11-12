package os.kei.hyper.home.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.OverlayIconCascadingDropdownMenu
import os.kei.hyper.home.ui.components.Os4DropdownPreference
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

class Os4DropdownMenuDeviceTest {
  private val backHost = Os4NavigationTestHost()
  private val compose = createAndroidComposeRule<ComponentActivity>()

  @get:Rule
  val rules: RuleChain = RuleChain.outerRule(ForegroundComposeHostRule()).around(compose)

  @Test
  @SdkSuppress(minSdkVersion = 33)
  fun glassMenuEscapesNestedTopBarAndKeepsCascadingSelection() {
    assumeTrue(isRuntimeShaderSupported())
    exerciseMenu(glass = true, direction = LayoutDirection.Ltr)
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  fun glassMenuKeepsWindowCoordinatesInRtl() {
    assumeTrue(isRuntimeShaderSupported())
    exerciseMenu(glass = true, direction = LayoutDirection.Rtl)
  }

  @Test
  fun nullBackdropUsesOpaqueNativeGroupedMenu() {
    exerciseMenu(glass = false, direction = LayoutDirection.Ltr)
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  fun glassPreferenceCommitsOnceAndBackKeepsSelection() {
    assumeTrue(isRuntimeShaderSupported())
    exercisePreference(glass = true)
  }

  @Test
  fun opaquePreferenceCommitsOnceAndBackKeepsSelection() {
    exercisePreference(glass = false)
  }

  private fun exercisePreference(glass: Boolean) {
    val selection = mutableIntStateOf(0)
    var callbacks = 0
    compose.setContent {
      backHost.Provide {
        MiuixTheme {
          val backdrop = if (glass) rememberLayerBackdrop() else null
          CompositionLocalProvider(LocalKeiHomeBackdrop provides backdrop) {
            Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
              Box(Modifier.fillMaxSize().padding(padding).then(
                backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier,
              ).background(MiuixTheme.colorScheme.surface)) {
                Os4DropdownPreference(
                  title = "Appearance",
                  items = listOf("Current", "Alternate"),
                  selectedIndex = selection.intValue,
                  modifier = Modifier.padding(start = 24.dp, top = 64.dp, end = 24.dp)
                    .testTag("preference"),
                  onSelectedIndexChange = {
                    callbacks++
                    selection.intValue = it
                  },
                )
              }
            }
          }
        }
      }
    }
    compose.onNodeWithTag("preference").performClick()
    compose.onNodeWithText("Alternate").assertIsDisplayed().performClick()
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(hasText("Current")).fetchSemanticsNodes().isEmpty()
    }
    compose.runOnIdle {
      assertEquals(1, selection.intValue)
      assertEquals(1, callbacks)
    }
    compose.onNodeWithTag("preference").performClick()
    compose.onNodeWithText("Current").assertIsDisplayed()
    completeBackEvent()
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(hasText("Current")).fetchSemanticsNodes().isEmpty()
    }
    compose.onNodeWithText("Alternate").assertIsDisplayed()
    compose.runOnIdle { assertEquals(1, callbacks) }
  }

  private fun exerciseMenu(glass: Boolean, direction: LayoutDirection) {
    val selection = mutableIntStateOf(0)
    var callbacks = 0
    compose.setContent {
      backHost.Provide {
      MiuixTheme {
        val backdrop = if (glass) rememberLayerBackdrop() else null
        val entries = listOf(
          DropdownEntry(listOf(DropdownItem(
            text = "Modes",
            children = listOf(
              DropdownItem(text = "Current", selected = selection.intValue == 0),
              DropdownItem(text = "Alternate", selected = selection.intValue == 1, onClick = {
                callbacks++
                selection.intValue = 1
              }),
              DropdownItem(text = "Unavailable", enabled = false),
            ),
          ))),
          DropdownEntry(listOf(DropdownItem(text = "Direct action", onClick = { callbacks++ }))),
        )
        CompositionLocalProvider(
          LocalKeiHomeBackdrop provides backdrop,
          LocalLayoutDirection provides direction,
        ) {
          Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
            // Offset the root host as well as clipping a nested Scaffold's top-bar region.
            Scaffold(
              modifier = Modifier.padding(start = 24.dp, top = 64.dp, end = 24.dp, bottom = 24.dp),
              contentWindowInsets = WindowInsets(0, 0, 0, 0),
              topBar = {
                Box(Modifier.fillMaxWidth().height(56.dp).clipToBounds().testTag("toolbar")) {
                  Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                      OverlayIconCascadingDropdownMenu(entries = entries) {
                        Text("Open menu")
                      }
                    }
                  }
                }
              },
            ) { padding ->
              Box(Modifier.fillMaxSize().padding(padding).then(
                backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier,
              ).background(MiuixTheme.colorScheme.surface))
            }
          }
        }
      }
      }
    }

    val anchor = compose.onNodeWithText("Open menu").fetchSemanticsNode().boundsInRoot
    val toolbar = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
    compose.onNodeWithText("Open menu").performClick()
    menuRow("Modes").assertIsDisplayed()
    menuRow("Direct action").assertIsDisplayed()
    val firstRow = menuRow("Modes").fetchSemanticsNode().boundsInRoot
    val lastRow = menuRow("Direct action").fetchSemanticsNode().boundsInRoot
    assertTrue("Menu must escape the clipped toolbar", lastRow.bottom > toolbar.bottom)
    assertTrue("Root offset must not be applied twice", firstRow.top < anchor.bottom)

    menuRow("Modes").performClick()
    menuRow("Current").assertIsSelected()
    menuRow("Unavailable").assertIsNotEnabled().performClick()
    menuRow("Alternate").assertIsDisplayed().performClick()
    compose.waitForIdle()
    compose.runOnIdle {
      assertEquals(1, selection.intValue)
      assertEquals(1, callbacks)
    }
    waitForMenuExit()

    compose.onNodeWithText("Open menu").performClick()
    menuRow("Modes").performClick()
    menuRow("Alternate").assertIsSelected()
    completeBackEvent()
    waitForSubmenuExit()
    menuRow("Direct action").assertIsDisplayed()
    completeBackEvent()
    waitForMenuExit()

    compose.onNodeWithText("Open menu").performClick()
    menuRow("Direct action").assertIsDisplayed()
    // Stay inside the deliberately inset Scaffold, below the popup.
    compose.onRoot().performTouchInput { click(Offset(center.x, height * 0.8f)) }
    waitForMenuExit()
    compose.runOnIdle { assertEquals(1, callbacks) }
  }

  private fun menuRow(text: String): SemanticsNodeInteraction {
    // Require one semantics node, including unplaced nodes: measurement clones must not
    // leak into accessibility. Do not filter by placement or selected state.
    val matcher = hasText(text)
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
    }
    return compose.onNode(matcher)
  }

  private fun waitForSubmenuExit() {
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(hasText("Current") or hasText("Alternate"))
        .fetchSemanticsNodes().isEmpty()
    }
  }

  private fun waitForMenuExit() {
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodes(hasText("Direct action") or hasText("Current") or hasText("Alternate"))
        .fetchSemanticsNodes().isEmpty()
    }
  }

  private fun completeBackEvent() {
    compose.runOnIdle { backHost.completeBack() }
    compose.waitForIdle()
  }
}
