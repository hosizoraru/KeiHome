package os.kei.hyper.home.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.activity.ComponentActivity
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import android.graphics.Bitmap
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainNavigation
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class OnboardingNavigationTest {
  @get:Rule val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val id = "onboarding_nav_${UUID.randomUUID()}"
  private val store by lazy { OnboardingPreferences(context, id, existingConfiguration = false) }
  private var currentPage = 0

  @After fun clean() {
    AppLocalStorage.open(context, id).close()
    MMKV.removeStorage(id)
  }

  private fun page(index: Int) {
    rule.waitUntil(10_000) {
      rule.onAllNodesWithTag("setup.page.$index").fetchSemanticsNodes()
        .singleOrNull()?.boundsInRoot?.left?.let { kotlin.math.abs(it) < 1f } == true
    }
    currentPage = index
    rule.onNodeWithTag("setup.page.$index").assertIsDisplayed()
  }

  private fun action(tag: String) = rule.onNode(hasTestTag("setup.$tag") and
    hasAnyAncestor(hasTestTag("setup.page.$currentPage")))

  private fun back() = rule.runOnUiThread {
    val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
      .filterIsInstance<ComponentActivity>().single()
    activity.onBackPressedDispatcher.onBackPressed()
  }

  private fun screenshot(name: String) {
    rule.waitForIdle()
    Thread.sleep(300)
    val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    File(context.cacheDir, "setup-$name.png").outputStream().use {
      check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    image.recycle()
  }

  @Test fun firstUseRoutesReturnToTheSameStepAndReplayKeepsCurrentPreferences() {
    val preferencesBefore = AppPreferences.get(context).state.value
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    page(0)
    screenshot("welcome")
    action("next").performClick()
    page(1)
    screenshot("framework")
    action("next").assertIsEnabled().performClick()
    page(2)
    rule.onNodeWithText(context.getString(R.string.pack_builtin)).performClick()
    rule.waitForIdle()
    back()
    page(2)
    action("next").performClick()
    page(3)
    rule.onNodeWithText(context.getString(R.string.setup_open_desktop)).performClick()
    rule.waitForIdle()
    back()
    page(3)
    action("previous").performClick()
    page(2)
    rule.runOnIdle { assertEquals(2, store.state.value) }
    action("next").performClick()
    page(3)
    action("next").performClick()
    page(4)
    screenshot("finish")
    rule.onNode(hasText(context.getString(R.string.settings_log_section)) and hasClickAction())
      .performScrollTo().performClick()
    rule.waitForIdle()
    back()
    page(4)
    action("next").performClick()
    rule.waitForIdle()
    rule.runOnIdle { assertEquals(OnboardingPreferences.COMPLETE, store.state.value) }
    rule.onNode(hasText(context.getString(R.string.navigation_settings)) and hasClickAction()).performClick()
    rule.onNode(hasText(context.getString(R.string.setup_title)) and hasClickAction())
      .performScrollTo().performClick()
    page(0)
    action("exit").performClick()
    rule.waitForIdle()
    rule.runOnIdle {
      val after = AppPreferences.get(context).state.value
      assertEquals(preferencesBefore, after)
      assertEquals(OnboardingPreferences.COMPLETE, store.state.value)
    }
  }

  @Test fun resumedSetupKeepsTheFooterReachableWithLargeTextAndCanBeDeferred() {
    store.visit(1)
    rule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
        KeiHomeTheme { MainNavigation(store) }
      }
    }
    page(1)
    rule.onNodeWithText(context.getString(R.string.scope_open_manager)).performScrollTo().assertIsDisplayed()
    action("next").assertIsDisplayed().assertIsEnabled()
    screenshot("large-text")
    action("exit").performClick()
    rule.waitForIdle()
    rule.runOnIdle { assertEquals(OnboardingPreferences.COMPLETE, store.state.value) }
    rule.onAllNodesWithTag("setup.page.1").fetchSemanticsNodes().let { assertEquals(0, it.size) }
  }
}
