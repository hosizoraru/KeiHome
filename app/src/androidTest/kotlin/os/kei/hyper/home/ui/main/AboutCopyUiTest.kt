package os.kei.hyper.home.ui.main

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.BuildConfig
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppTopBarBlur
import os.kei.hyper.home.ui.components.LocalKeiHomeTopBarBlur
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class AboutCopyUiTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val application: Application = ApplicationProvider.getApplicationContext()
  private val viewModelStore = ViewModelStore()
  private val clipboard = application.getSystemService(ClipboardManager::class.java)
  private var previousClip: ClipData? = null
  private var clipboardCaptured = false

  @After fun clean() {
    rule.runOnIdle {
      viewModelStore.clear()
      if (clipboardCaptured) {
        previousClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
      }
    }
  }

  @Test fun fixedCopyButtonSurvivesScrollingAndHeroHasNoCopyAction() {
    showAbout()
    val description = application.getString(R.string.polish_copy_version)
    rule.onNodeWithText(description).assertDoesNotExist()
    rule.onNodeWithContentDescription(description).assertHasClickAction().assertIsDisplayed()
    val hero = rule.onNodeWithTag("about.hero")
    hero.assertHasNoClickAction().assert(!hasAnyDescendant(hasClickAction()))
    hero.performTouchInput { click() }
    rule.runOnIdle { assertEquals("hero-copy-sentinel", clipboard.primaryClip?.getItemAt(0)?.text.toString()) }
    val copy = rule.onNodeWithTag("aboutCopy").assertIsDisplayed()
    val fixedBounds = copy.fetchSemanticsNode().boundsInRoot
    val viewport = rule.onNodeWithTag("about.viewport").fetchSemanticsNode().boundsInRoot
    assertTrue("Copy belongs in the upper-right toolbar", fixedBounds.center.x > viewport.center.x &&
      fixedBounds.top < viewport.top + viewport.height / 4)
    copy.performClick()
    assertCopiedPayload()
    rule.onNodeWithTag("about.scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
    rule.mainClock.advanceTimeBy(1_000)
    rule.waitForIdle()
    assertScrolled()
    copy.assertIsDisplayed().assertHasClickAction()
    assertEquals(fixedBounds, copy.fetchSemanticsNode().boundsInRoot)
    copy.performClick()
    assertCopiedPayload()
  }

  @Test fun environmentDeepLinkKeepsCopyFixedAndOperable() {
    showAbout(route = "about/environment")
    val copy = rule.onNodeWithTag("aboutCopy").assertIsDisplayed()
    val fixedBounds = copy.fetchSemanticsNode().boundsInRoot
    rule.mainClock.advanceTimeBy(2_000)
    rule.waitForIdle()
    assertScrolled()
    copy.assertIsDisplayed().performClick()
    assertEquals(fixedBounds, copy.fetchSemanticsNode().boundsInRoot)
    assertCopiedPayload()
  }

  @Test fun copyRemainsAvailableWhenToolbarBlurIsDisabled() {
    showAbout(blur = AppTopBarBlur.OFF)
    rule.onNodeWithTag("aboutCopy").assertIsDisplayed().assertHasClickAction().performClick()
    assertCopiedPayload()
  }

  private fun showAbout(route: String = SettingsSection.ABOUT.route, blur: AppTopBarBlur = AppTopBarBlur.PROGRESSIVE) {
    // Advance explicitly so the animated backdrop does not drive the test clock.
    rule.mainClock.autoAdvance = false
    rule.runOnIdle {
      previousClip = clipboard.primaryClip
      clipboardCaptured = true
      clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", "hero-copy-sentinel"))
    }
    rule.setContent {
      val model = ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory(application))
        .get(HomeViewModel::class.java)
      KeiHomeTheme {
        CompositionLocalProvider(LocalKeiHomeTopBarBlur provides blur) {
          Box(Modifier.fillMaxWidth().height(520.dp).testTag("about.viewport")) {
            SettingsDestinationScreen(model, route, onBack = {}, onNavigate = {}, onOpenPackSection = {})
          }
        }
      }
    }
    rule.mainClock.advanceTimeBy(64)
    rule.waitForIdle()
  }

  private fun assertScrolled() {
    val range = rule.onNodeWithTag("about.scroll").fetchSemanticsNode()
      .config[SemanticsProperties.VerticalScrollAxisRange]
    assertTrue("The About content must actually scroll", range.value() > 0f)
  }

  private fun assertCopiedPayload() {
    rule.runOnIdle {
      val appName = application.getString(R.string.app_name)
      val version = application.getString(R.string.polish_about_version,
        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
      assertEquals(appName, clipboard.primaryClip?.description?.label?.toString())
      assertEquals("$appName $version\n${BuildConfig.APPLICATION_ID}",
        clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
  }
}
