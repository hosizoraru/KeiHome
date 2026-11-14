package os.kei.hyper.home.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainNavigation
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class OnboardingNavigationTest {
  @get:Rule(order = 0) val foregroundHost = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createComposeRule()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val id = "onboarding_nav_${UUID.randomUUID()}"
  private val store by lazy { OnboardingPreferences(context, id, existingConfiguration = false) }
  private var currentPage = 0
  private var capturePrefix = "standard"

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
    settleTransition()
    val viewport = action("content").fetchSemanticsNode().boundsInRoot
    val header = action("header").fetchSemanticsNode().boundsInRoot
    assertTrue("Guide content must scroll behind its floating header, without a fixed cutoff",
      viewport.top < header.top && viewport.bottom > header.bottom)
  }

  private fun action(tag: String) = rule.onNode(hasTestTag("setup.$tag") and
    hasAnyAncestor(hasTestTag("setup.page.$currentPage")))

  private fun openLink(title: Int) {
    val link = rule.onNode(androidx.compose.ui.test.hasText(context.getString(title)) and
      androidx.compose.ui.test.hasClickAction()).performScrollTo()
    val toolbar = action("toolbar").fetchSemanticsNode().boundsInRoot
    val safeBottom = toolbar.top - 48f * context.resources.displayMetrics.density
    val bottom = link.fetchSemanticsNode().boundsInRoot.bottom
    if (bottom > safeBottom) {
      // Scroll past the floating controls and fade, just as a further upward swipe would.
      action("content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, bottom - safeBottom) }
      rule.waitForIdle()
    }
    val center = link.fetchSemanticsNode().boundsInRoot.center
    val viewport = action("content").fetchSemanticsNode().boundsInRoot
    assertTrue("Link must stay inside the unobscured viewport", viewport.contains(center))
    assertTrue("Link must not be covered by the floating toolbar", center.y < toolbar.top)
    link.performClick()
  }

  private fun destination(tag: String) {
    rule.waitUntil(30_000) {
      val matches = rule.onAllNodesWithTag(tag)
      matches.fetchSemanticsNodes().indices.any { matches[it].isDisplayed() }
    }
    settleTransition()
  }

  private fun packPage(section: String) = destination("icon-packs.$section")

  private fun settleTransition() {
    // MIUIX can still block pointer input after the clipped scene reaches the viewport.
    rule.waitForIdle()
    rule.mainClock.advanceTimeBy(1_000)
    android.os.SystemClock.sleep(1_000)
    rule.waitForIdle()
  }

  private fun checkContentEnd() {
    action("content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
    rule.waitForIdle()
    val end = action("end").fetchSemanticsNode().boundsInRoot
    val toolbar = action("toolbar").fetchSemanticsNode().boundsInRoot
    val viewport = action("content").fetchSemanticsNode().boundsInRoot
    assertTrue("content continues behind the floating controls instead of ending in a blank band",
      viewport.bottom > toolbar.bottom)
    check(end.bottom <= toolbar.top + 1f) { "Step $currentPage content ends behind the floating toolbar" }
    action("next").assertIsDisplayed().assertIsEnabled()
    screenshot("$capturePrefix-step-$currentPage-end")
  }

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
    checkContentEnd()
    action("next").performClick()
    page(1)
    screenshot("framework")
    rule.onNodeWithText(context.getString(R.string.setup_os4_framework_manager))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.setup_os4_activation))
      .performScrollTo().assertIsDisplayed()
    openLink(R.string.setup_os4_scope)
    destination("scope.page.refresh")
    rule.onNodeWithTag("scope.page.refresh").assertIsDisplayed()
    back()
    page(1)
    checkContentEnd()
    action("next").assertIsEnabled().performClick()
    page(2)
    rule.onNodeWithText(context.getString(R.string.setup_native_icons_title))
      .performScrollTo().assertIsDisplayed()
    openLink(R.string.icon_pack_library)
    packPage("library")
    rule.onNodeWithText(context.getString(R.string.pack_builtin)).performClick()
    packPage("builtin")
    back()
    packPage("library")
    back()
    page(2)
    for ((title, section) in listOf(
      R.string.perfect_icons_title to "perfect",
      R.string.apple_pack_title to "apple",
    )) {
      openLink(title)
      packPage(section)
      back()
      page(2)
    }
    openLink(R.string.perfect_git_resources)
    destination("perfect.resources.filter")
    back()
    page(2)
    openLink(R.string.navigation_monet)
    packPage("monet")
    back()
    page(2)
    openLink(R.string.pack_completion_title)
    packPage("fallback")
    back()
    page(2)
    openLink(R.string.icon_mask_entry_title)
    destination("icon-mask.page")
    back()
    page(2)
    openLink(R.string.preserved_icons_title)
    destination("preserved-icons.page")
    back()
    page(2)
    openLink(R.string.recommended_mappings_title)
    destination("recommended-mappings.page")
    back()
    page(2)
    openLink(R.string.setup_os4_icons_more)
    packPage("settings")
    rule.onNodeWithText(context.getString(R.string.pack_auto_apply)).assertIsDisplayed()
    back()
    page(2)
    checkContentEnd()
    action("next").performClick()
    page(3)
    openLink(R.string.setup_open_desktop)
    destination("desktop.settings")
    val desktopContent = rule.onNodeWithTag("setup.desktop.content").fetchSemanticsNode().boundsInRoot
    val desktopToolbar = rule.onNodeWithTag("setup.desktop.topbar").fetchSemanticsNode().boundsInRoot
    assertTrue("Desktop overview must scroll beneath its toolbar", desktopContent.top < desktopToolbar.bottom)
    back()
    page(3)
    for ((title, section) in listOf(
      R.string.setup_os4_desktop_dock to "dock",
      R.string.setup_os4_desktop_appearance to "appearance",
      R.string.setup_os4_desktop_launch to "icons",
    )) {
      openLink(title)
      destination("desktop.page.$section")
      rule.onNodeWithTag("desktop.page.$section").assertIsDisplayed()
      back()
      page(3)
    }
    checkContentEnd()
    action("previous").performClick()
    page(2)
    rule.runOnIdle {
      assertEquals(2, store.state.value)
      assertEquals(2, OnboardingPreferences(context, id, existingConfiguration = false).state.value)
    }
    action("next").performClick()
    page(3)
    action("next").performClick()
    page(4)
    screenshot("finish")
    checkContentEnd()
    openLink(R.string.settings_hook_section)
    destination("scope.page.refresh")
    rule.onNodeWithTag("scope.page.refresh").assertIsDisplayed()
    back()
    page(4)
    openLink(R.string.settings_log_section)
    destination("settings.logs.export")
    rule.onNodeWithTag("settings.logs.export").assertIsDisplayed()
    back()
    page(4)
    openLink(R.string.settings_cache_section)
    settleTransition()
    rule.onNodeWithTag("settings.cache.clear").assertExists()
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
      assertEquals(OnboardingPreferences.COMPLETE,
        OnboardingPreferences(context, id, existingConfiguration = false).state.value)
    }
  }

  @Test fun storageHelpSheetDismissesAndReopensWithoutChangingTheGuideOrSettings() {
    val before = AppPreferences.get(context).state.value
    store.visit(2)
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    page(2)
    repeat(2) {
      openLink(R.string.setup_local_icons_title)
      destination("setup.storage-help.sheet")
      rule.onNodeWithText(context.getString(R.string.setup_local_icons_body)).assertIsDisplayed()
      rule.onNodeWithText(context.getString(R.string.generated_pack_retention_note))
        .performScrollTo().assertIsDisplayed()
      screenshot("storage-help-$it")
      back()
      rule.waitUntil(10_000) {
        rule.onAllNodesWithTag("setup.storage-help.sheet").fetchSemanticsNodes().isEmpty()
      }
      page(2)
      assertEquals(2, store.state.value)
      assertEquals(before, AppPreferences.get(context).state.value)
    }
  }

  @Test fun rapidContinueDoesNotDuplicateTheNextStep() {
    rule.setContent { KeiHomeTheme { MainNavigation(store) } }
    page(0)
    val click = checkNotNull(action("next").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    rule.runOnUiThread { click(); click() }
    page(1)
    assertEquals(1, store.state.value)
    val previous = checkNotNull(action("previous").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    rule.runOnUiThread { previous(); previous() }
    page(0)
    assertEquals(0, store.state.value)
  }

  @Test fun resumedSetupKeepsTheFooterReachableWithLargeTextAndCanBeDeferred() {
    capturePrefix = "large"
    store.visit(1)
    rule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
        KeiHomeTheme { MainNavigation(store) }
      }
    }
    page(1)
    action("previous").performClick()
    page(0)
    checkContentEnd()
    action("next").performClick()
    page(1)
    rule.onNodeWithText(context.getString(R.string.setup_os4_framework_manager))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.setup_os4_activation))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.setup_os4_scope))
      .performScrollTo().assertIsDisplayed()
    action("next").assertIsDisplayed().assertIsEnabled()
    screenshot("large-text")
    checkContentEnd()
    action("next").performClick()
    page(2)
    rule.onNodeWithText(context.getString(R.string.setup_native_icons_title))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.pack_completion_title))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.perfect_icons_title))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.apple_pack_title))
      .performScrollTo().assertIsDisplayed()
    rule.onNodeWithText(context.getString(R.string.setup_local_icons_title))
      .performScrollTo().assertIsDisplayed()
    action("next").assertIsDisplayed().assertIsEnabled()
    screenshot("large-icons")
    checkContentEnd()
    action("next").performClick()
    page(3)
    for (title in listOf(
      R.string.setup_os4_desktop_dock,
      R.string.setup_os4_desktop_appearance,
      R.string.setup_os4_desktop_launch,
    )) {
      rule.onNodeWithText(context.getString(title)).performScrollTo().assertIsDisplayed()
    }
    checkContentEnd()
    action("next").performClick()
    page(4)
    checkContentEnd()
    action("exit").performClick()
    rule.waitForIdle()
    rule.runOnIdle { assertEquals(OnboardingPreferences.COMPLETE, store.state.value) }
    rule.onAllNodesWithTag("setup.page.1").fetchSemanticsNodes().let { assertEquals(0, it.size) }
  }
}
