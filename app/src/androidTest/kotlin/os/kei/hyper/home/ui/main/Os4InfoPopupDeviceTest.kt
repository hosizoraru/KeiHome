package os.kei.hyper.home.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.ui.components.KeiHomeScaffold
import os.kei.hyper.home.ui.components.LocalKeiHomeBackdrop
import os.kei.hyper.home.ui.components.Os4InfoPopup
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Integration coverage against the real MIUIX 19b5b831 transform and root popup host. */
class Os4InfoPopupDeviceTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val compose = createComposeRule()
  private val backHost = Os4NavigationTestHost()
  private val showTrigger = mutableStateOf(true)

  @Test fun nativeMorphStartsAtAnchorAndBackKeepsHostUntilExitCompletes() {
    showInfo(glass = true)
    val trigger = compose.onNodeWithContentDescription(TRIGGER)
    val source = trigger.fetchSemanticsNode().boundsInRoot
    compose.mainClock.autoAdvance = false
    trigger.performClick()
    compose.mainClock.advanceTimeBy(96)
    compose.waitForIdle()
    val opening = compose.onNodeWithTag("info.popup.title").fetchSemanticsNode().boundsInRoot
    compose.mainClock.advanceTimeBy(2_000)
    val settled = compose.onNodeWithTag("info.popup.title").fetchSemanticsNode().boundsInRoot
    assertTrue("Native foreground must grow out of the icon anchor", opening.width < settled.width)
    assertTrue("The settled explanation must exceed its icon anchor", settled.width > source.width)

    compose.runOnIdle { backHost.completeBack() }
    compose.waitForIdle()
    compose.onNodeWithTag("info.popup.host").assertExists()
    compose.mainClock.advanceTimeBy(32)
    compose.onNodeWithTag("info.popup.host").assertExists()
    compose.mainClock.advanceTimeBy(2_000)
    compose.waitForIdle()
    compose.onNodeWithTag("info.popup.host").assertDoesNotExist()
    trigger.assertIsDisplayed().assertIsFocused()
    compose.mainClock.autoAdvance = true
  }

  @Test fun opaqueFallbackRetainsPageTypographyAndSupportsAccessibleDismiss() {
    showInfo(glass = false, customTypography = true)
    val trigger = compose.onNodeWithContentDescription(TRIGGER)
    trigger.assertHasClickAction().performClick()
    compose.onNodeWithTag("info.popup.title").assertIsFocused()
    assertLayout("info.popup.title", 27, FontFamily.Monospace)
    assertLayout("info.popup.summary", 23, FontFamily.Monospace)
    compose.onNodeWithTag("info.popup.title")
      .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
    assertClosed()
    trigger.assertIsFocused()
  }

  @Test fun keyboardEscapeOutsideReleaseAndCancelledBackRemainUsable() {
    showInfo(glass = true)
    val trigger = compose.onNodeWithContentDescription(TRIGGER)
    trigger.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
    trigger.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
    compose.onNodeWithTag("info.popup.title").assertIsFocused()
    compose.runOnIdle {
      backHost.cancelBack()
    }
    compose.onNodeWithTag("info.popup.title").assertIsDisplayed()
    compose.onNodeWithTag("info.popup.title").performKeyInput { pressKey(Key.Escape) }
    assertClosed()
    trigger.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
    compose.onNodeWithTag("info.popup.title").assertIsDisplayed()
    compose.onRoot().performTouchInput {
      down(Offset(2f, 2f))
      cancel()
    }
    compose.onNodeWithTag("info.popup.title").assertIsDisplayed()
    compose.onRoot().performTouchInput { click(Offset(2f, 2f)) }
    assertClosed()
  }

  @Test fun largeTextNearBottomStaysInViewportAndScrolls() {
    showInfo(glass = false, largeText = true)
    compose.onNodeWithContentDescription(TRIGGER).performClick()
    val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
    val title = compose.onNodeWithTag("info.popup.title").fetchSemanticsNode().boundsInRoot
    val summary = compose.onNodeWithTag("info.popup.summary").fetchSemanticsNode().boundsInRoot
    assertTrue(title.left >= root.left && title.right <= root.right)
    assertTrue(title.top >= root.top && title.bottom <= root.bottom)
    assertTrue(summary.left >= root.left && summary.right <= root.right && summary.bottom <= root.bottom)
    val scroller = compose.onAllNodes(hasScrollAction()).onFirst()
    scroller.performTouchInput { swipeUp() }
    compose.runOnIdle { backHost.completeBack() }
    assertClosed()
  }

  @Test fun removingTriggerAlsoRemovesItsRootPortal() {
    showInfo(glass = true)
    compose.onNodeWithContentDescription(TRIGGER).performClick()
    compose.onNodeWithTag("info.popup.title").assertIsDisplayed()
    compose.runOnIdle { showTrigger.value = false }
    assertClosed()
  }

  private fun showInfo(glass: Boolean, customTypography: Boolean = false, largeText: Boolean = false) {
    compose.setContent {
      KeiHomeTheme {
        backHost.Provide {
          val source = rememberLayerBackdrop()
          KeiHomeScaffold { padding ->
            val density = LocalDensity.current
            val typography = MiuixTheme.textStyles
            // Both providers are deliberately below Scaffold's root popup host.
            CompositionLocalProvider(
              LocalKeiHomeBackdrop provides if (glass) source else null,
              LocalKeiHomeDarkTheme provides true,
              LocalDensity provides Density(density.density, if (largeText) 2f else density.fontScale),
            ) {
              MiuixTheme(textStyles = if (customTypography) typography.copy(
                subtitle = typography.subtitle.copy(fontSize = 27.sp, fontFamily = FontFamily.Monospace),
                body2 = typography.body2.copy(fontSize = 23.sp, fontFamily = FontFamily.Monospace),
              ) else typography) {
                Box(
                  Modifier.fillMaxSize().padding(padding)
                    .layerBackdrop(source).background(Color(0xFFB7D0C5)),
                  contentAlignment = Alignment.BottomEnd,
                ) {
                  if (showTrigger.value) Os4InfoPopup(
                    title = "Cache explanation",
                    summary = if (largeText) SUMMARY.repeat(12) else SUMMARY,
                    contentDescription = TRIGGER,
                    modifier = Modifier.padding(12.dp),
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  private fun assertClosed() {
    compose.waitForIdle()
    compose.onNodeWithTag("info.popup.host").assertDoesNotExist()
    compose.onNodeWithTag("info.popup.title").assertDoesNotExist()
  }

  private fun assertLayout(tag: String, size: Int, family: FontFamily) {
    compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
      val layouts = mutableListOf<TextLayoutResult>()
      assertTrue(get(layouts))
      assertEquals(size.sp, layouts.single().layoutInput.style.fontSize)
      assertEquals(family, layouts.single().layoutInput.style.fontFamily)
    }
  }

  private companion object {
    const val TRIGGER = "About cache"
    const val SUMMARY = "Temporary previews can be recreated without removing imported icon resources. "
  }
}
