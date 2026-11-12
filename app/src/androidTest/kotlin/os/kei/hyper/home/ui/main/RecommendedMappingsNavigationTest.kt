package os.kei.hyper.home.ui.main

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import os.kei.hyper.home.MainNavigation
import os.kei.hyper.home.R
import os.kei.hyper.home.data.AppLocalStorage
import os.kei.hyper.home.data.OnboardingPreferences
import os.kei.hyper.home.data.ReleaseNotesPreferences
import os.kei.hyper.home.ui.theme.KeiHomeTheme

class RecommendedMappingsNavigationTest {
  @get:Rule(order = 0) val foreground = ForegroundComposeHostRule()
  @get:Rule(order = 1) val rule = createAndroidComposeRule<ComponentActivity>()
  private val context: Context get() = ApplicationProvider.getApplicationContext()
  private val stores = mutableListOf<String>()

  @After fun removeTestStores() {
    stores.forEach { id ->
      AppLocalStorage.open(context, id).close()
      MMKV.removeStorage(id)
    }
  }

  @Test fun fallbackKeepsPreservedEntryAndOpensRecommendedMappingsWithBackNavigation() {
    setMainContent()
    selectTab(R.string.navigation_icon_packs)
    rule.onNodeWithText(context.getString(R.string.pack_completion_title)).performScrollTo().performClick()
    rule.onNodeWithTag("icon-packs.open-preserved-icons").assertIsDisplayed()
    rule.onNodeWithTag("icon-packs.system-name-matching").assertDoesNotExist()
    rule.onNodeWithTag("icon-packs.open-recommended-mappings").assertIsDisplayed().performClick()
    rule.onNodeWithTag("recommended-mappings.page").assertIsDisplayed()
    rule.onNodeWithTag("icon-packs.system-name-matching").assertExists()
    rule.onNodeWithTag("recommended-mappings.telegram").assertExists()
    rule.onNodeWithTag("recommended-mappings.coloros-theme-store").assertExists()
    rule.onNodeWithTag("recommended-mappings.back").performClick()
    rule.onNodeWithTag("icon-packs.open-recommended-mappings").assertIsDisplayed()
    rule.onNodeWithTag("icon-packs.open-preserved-icons").performClick()
    rule.onNodeWithTag("preserved-icons.search").assertIsDisplayed()
  }

  @Test fun settingsSearchOpensPackDestinationAndBackPreservesQuery() {
    setMainContent()
    selectTab(R.string.navigation_settings)
    rule.onNodeWithTag("settings.search", useUnmergedTree = true)
      .performTextInput("com.miui.themestore")
    rule.onNodeWithTag("settings.entry.recommended-mappings").performScrollTo().performClick()
    rule.onNodeWithTag("recommended-mappings.page").assertIsDisplayed()
    rule.onNodeWithTag("recommended-mappings.back").performClick()
    rule.onNodeWithTag("settings.entry.recommended-mappings").assertIsDisplayed()
    rule.onNodeWithText("com.miui.themestore", useUnmergedTree = true).assertIsDisplayed()
  }

  private fun selectTab(@StringRes labelRes: Int) {
    val label = context.getString(labelRes)
    // Glass names tabs by content description; iOS merges the visible label.
    val tab = rule.onNode(isSelectable() and (hasContentDescription(label) or hasText(label)))
    tab.assertIsDisplayed().performClick().assertIsSelected()
    rule.waitForIdle()
  }

  private fun setMainContent() {
    fun storageId() = "recommended_navigation_${UUID.randomUUID()}".also { stores += it }
    val setup = OnboardingPreferences(context, storageId(), existingConfiguration = true)
    val releaseNotes = ReleaseNotesPreferences(context, storageId(), updatedInstallation = false,
      allowLegacyUpgrade = false)
    val host = Os4NavigationTestHost()
    rule.setContent {
      host.Provide {
        KeiHomeTheme {
          MainNavigation(setup, releaseNotes)
        }
      }
    }
  }
}
