package os.kei.hyper.home.ui.main

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.tencent.mmkv.MMKV
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainNavigation
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.AppPreferences
import os.kei.hyper.home.data.AppTopBarBlur
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.data.ReleaseNotesPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme
import os.kei.hyper.home.ui.theme.LocalKeiHomeDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ReleaseNotesNavigationTest {
  @get:Rule(order = 0) val host = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val stores = mutableListOf<String>()
  private var previousBlur: AppTopBarBlur? = null
  private fun id() = "release_ui_${UUID.randomUUID()}".also(stores::add)
  private fun setup(complete: Boolean) =
    OnboardingPreferences(context, id(), existingConfiguration = complete)

  @After fun clean() {
    previousBlur?.let { AppPreferences.get(context).setTopBarBlur(it) }
    stores.forEach { AppLocalStorage.open(context, it).close(); MMKV.removeStorage(it) }
  }

  private fun shot(name: String) {
    rule.waitForIdle()
    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
    File(context.cacheDir, "release-$name.png").outputStream().use {
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
    bitmap.recycle()
  }

  @Test fun prefixUpgradeOpensOnceAndManualSettingsEntryRemainsAvailable() {
    val storage = id()
    ReleaseNotesPreferences(context, storage, "1.4.0", false)
    val update = ReleaseNotesPreferences(context, storage, "1.8.0", true)
    val onboarding = setup(true)
    val before = AppPreferences.get(context).state.value
    rule.setContent { KeiHomeTheme { MainNavigation(onboarding, update) } }
    rule.onNodeWithTag("release-notes.page").assertIsDisplayed()
    rule.onNodeWithTag("release-notes.body.1.8.0").assertExists()
    rule.onNodeWithTag("release-notes.body.1.7.0").assertExists()
    rule.onNodeWithTag("release-notes.body.1.6.0").assertExists()
    rule.onNodeWithTag("release-notes.body.1.5.0").assertDoesNotExist()
    val version = rule.onNodeWithTag("release-notes.version.1.8.0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val date = rule.onNodeWithTag("release-notes.date.1.8.0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertTrue("Date must be on the right of the version", date.left >= version.right)
    assertTrue("Date and version must share a row", date.top < version.bottom && date.bottom > version.top)
    shot("upgrade")
    rule.onNodeWithTag("release-notes.continue").performClick()
    rule.onNodeWithTag("release-notes.page").assertDoesNotExist()
    assertEquals("", update.state.value.pendingVersion)
    assertEquals("", ReleaseNotesPreferences(context, storage, "1.8.0+g7654321.b9999", true).state.value.pendingVersion)
    rule.onNode(hasText(context.getString(R.string.navigation_settings)) and isSelectable()).performClick()
    rule.onNodeWithTag("settings.entry.release-notes").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.page").assertIsDisplayed()
    rule.onNodeWithTag("release-notes.continue").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.body.1.8.0").assertExists()
    rule.onNodeWithTag("release-notes.body.1.7.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.body.1.6.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.toggle.1.8.0").performClick()
    rule.onNodeWithTag("release-notes.body.1.8.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.date.1.5.0", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    shot("manual-collapsed")
    rule.onNodeWithTag("release-notes.toggle.1.5.0").performClick()
    rule.onNodeWithTag("release-notes.body.1.5.0").assertExists()
    rule.onNodeWithTag("release-notes.back").performClick()
    rule.onNodeWithTag("settings.entry.release-notes").assertExists()
    assertEquals(before, AppPreferences.get(context).state.value)
  }

  @Test fun independentDisclosuresSurviveRestorationAndOlderVersionsExcludeFutureCards() {
    val restore = StateRestorationTester(rule)
    restore.setContent { KeiHomeTheme { ReleaseNotesScreen({}, currentVersion = "1.5.0") } }
    rule.onNodeWithTag("release-notes.body.1.5.0").assertExists()
    rule.onNodeWithTag("release-notes.release.1.6.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.toggle.1.4.0").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.body.1.4.0").assertExists()
    restore.emulateSavedInstanceStateRestore()
    rule.onNodeWithTag("release-notes.body.1.4.0").assertExists()
    rule.onNodeWithTag("release-notes.body.1.5.0").assertExists()
    rule.onNodeWithTag("release-notes.toggle.1.5.0").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.body.1.5.0").assertDoesNotExist()
    restore.emulateSavedInstanceStateRestore()
    rule.onNodeWithTag("release-notes.body.1.5.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.body.1.4.0").assertExists()
  }

  @Test fun firstInstallationUsesGuideWithoutOpeningReleaseNotesAfterCompletion() {
    val onboarding = setup(false)
    val notes = ReleaseNotesPreferences(context, id(), "1.7.0", false)
    rule.setContent { KeiHomeTheme { MainNavigation(onboarding, notes) } }
    rule.onNodeWithTag("setup.page.0").assertIsDisplayed()
    rule.onNodeWithTag("release-notes.page").assertDoesNotExist()
    rule.onNodeWithTag("setup.exit").performClick()
    rule.onNodeWithTag("release-notes.page").assertDoesNotExist()
    assertEquals("", notes.state.value.pendingVersion)
  }

  @Test fun upgradeDuringUnfinishedGuideWaitsForTheGuideThenShowsTheChangedPrefix() {
    val storage = id()
    ReleaseNotesPreferences(context, storage, "1.6.0", false, allowLegacyUpgrade = false)
    val update = ReleaseNotesPreferences(context, storage, "1.7.0", true, allowLegacyUpgrade = false)
    val onboarding = setup(false)
    rule.setContent { KeiHomeTheme { MainNavigation(onboarding, update) } }
    rule.onNodeWithTag("setup.page.0").assertIsDisplayed()
    rule.onNodeWithTag("release-notes.page").assertDoesNotExist()
    rule.onNodeWithTag("setup.exit").performClick()
    rule.onNodeWithTag("release-notes.page").assertIsDisplayed()
    rule.onNodeWithTag("release-notes.continue").performClick()
    assertEquals("", update.state.value.pendingVersion)
  }

  @Test fun largeTextKeepsHistoricalContentAboveTheContinueAction() {
    rule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
        KeiHomeTheme { ReleaseNotesScreen({}, onContinue = {}) }
      }
    }
    rule.onNodeWithTag("release-notes.toggle.1.7.0").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.toggle.1.4.0").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.end").performScrollTo().assertIsDisplayed()
    rule.onNodeWithTag("release-notes.content").performTouchInput { swipeUp() }
    rule.waitForIdle()
    val end = rule.onNodeWithTag("release-notes.end").fetchSemanticsNode().boundsInRoot
    val action = rule.onNodeWithTag("release-notes.continue").fetchSemanticsNode().boundsInRoot
    assertTrue("End of historical notes must remain above the floating action", end.bottom <= action.top)
    shot("large-end")
  }

  @Test fun lightThemeRetainsReadableCollapsedDates() {
    rule.setContent {
      val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.Light) }
      CompositionLocalProvider(LocalKeiHomeDarkTheme provides false) {
        MiuixTheme(controller = controller) { ReleaseNotesScreen({}, currentVersion = "1.6.0") }
      }
    }
    rule.onNodeWithTag("release-notes.toggle.1.6.0").performScrollTo().performClick()
    rule.onNodeWithTag("release-notes.body.1.6.0").assertDoesNotExist()
    rule.onNodeWithTag("release-notes.date.1.6.0", useUnmergedTree = true).assertIsDisplayed()
    rule.onNodeWithTag("release-notes.date.1.4.0", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    shot("light-collapsed")
  }

  @Test fun scrolledNotesPassBehindTheToolbarAcrossAllMaterialSettings() {
    val preferences = AppPreferences.get(context)
    previousBlur = preferences.state.value.topBarBlur
    rule.setContent { KeiHomeTheme { ReleaseNotesScreen({}) } }
    rule.onNodeWithTag("release-notes.toggle.1.4.0").performScrollTo().performClick()
    for (mode in AppTopBarBlur.entries) {
      rule.runOnIdle { preferences.setTopBarBlur(mode) }
      rule.onNodeWithTag("release-notes.content").performTouchInput { swipeUp() }
      rule.waitForIdle()
      val viewport = rule.onNodeWithTag("release-notes.content").fetchSemanticsNode().boundsInRoot
      val toolbar = rule.onNodeWithTag("release-notes.topbar").fetchSemanticsNode().boundsInRoot
      assertTrue("Scrollable content must extend beneath the $mode toolbar", viewport.top < toolbar.bottom)
      rule.onNodeWithTag("release-notes.back").assertIsDisplayed().assertHasClickAction()
      shot("toolbar-${mode.name.lowercase()}")
    }
  }
}
